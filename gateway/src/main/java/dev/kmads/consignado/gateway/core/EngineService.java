package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.ClusterControl;
import dev.kmads.consignado.gateway.ports.ClusterObserver;
import dev.kmads.consignado.gateway.ports.EngineStore;
import dev.kmads.consignado.gateway.ports.SessionStore;
import dev.kmads.consignado.gateway.ports.Upstream;
import dev.kmads.consignado.gateway.ports.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The engine: access windows and the cluster state machine
 * (stopped, starting, running, stopping, cooldown; "paused" is a flag that is shown as a state).
 * The gateway only reads, starts and stops the cluster. It never deletes anything.
 */
public final class EngineService {
    public static final String READINESS_PATH = "actuator/health/readiness";
    private static final Duration OBSERVE_EVERY = Duration.ofSeconds(3);
    private static final Duration START_RETRY_AFTER = Duration.ofMinutes(2);
    public static final Duration BUDGET_CHECK_EVERY = Duration.ofMinutes(30);
    private static final String CLUSTER = "aks-consignado";
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("consignado.gateway");

    public record WindowResult(Session session, boolean created) {}

    private final UserStore users;
    private final SessionStore sessions;
    private final EngineStore engine;
    private final ClusterControl cluster;
    private final ClusterObserver observer;
    private final Upstream upstream;
    private final AuditLog audit;
    private final Settings settings;
    private final Clock clock;
    private final Supplier<String> ids;

    public EngineService(UserStore users, SessionStore sessions, EngineStore engine, ClusterControl cluster,
                         ClusterObserver observer, Upstream upstream, AuditLog audit, Settings settings, Clock clock,
                         Supplier<String> ids) {
        this.users = users;
        this.sessions = sessions;
        this.engine = engine;
        this.cluster = cluster;
        this.observer = observer;
        this.upstream = upstream;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.ids = ids;
    }

    // ------------------------------------------------------------------ views

    public Views.Engine view() {
        return view(engine.load());
    }

    public Views.Engine view(EngineRecord rec) {
        String state = rec.state;
        if (EngineRecord.COOLDOWN.equals(state) && cooldownLeft(rec, clock.instant()) <= 0) state = EngineRecord.STOPPED;
        if (rec.paused && (EngineRecord.STOPPED.equals(state) || EngineRecord.COOLDOWN.equals(state))) {
            state = EngineRecord.PAUSED;
        }
        Instant since = rec.since == null ? clock.instant() : rec.since;
        return new Views.Engine(state, Times.iso(since), rec.activeSessions, rec.paused ? rec.pausedReason : null,
                rec.paused);
    }

    // ------------------------------------------------------------ POST /sessions

    /** Starts an access window. identityMs and keyVaultMs are the measured times of this request. */
    public WindowResult requestWindow(User user, boolean admin, long identityMs, long keyVaultMs,
                                      boolean keyVaultCached, String ipHash) {
        Instant now = clock.instant();
        if (!admin) {
            if (User.BLOCKED.equals(user.status)) throw ApiException.conflict("blocked", "This account is blocked");
            if (User.HELD.equals(user.status)) {
                throw ApiException.conflict("held", "This account waits for a check by the owner");
            }
            if (!user.profileComplete()) {
                throw ApiException.conflict("profile-incomplete", "Complete your profile first");
            }
        }
        EngineRecord rec = engine.load();
        if (rec.paused) throw ApiException.conflict("paused", "New access windows are paused now");

        if (user.activeSessionId != null) {
            Session existing = sessions.find(user.activeSessionId).orElse(null);
            if (existing != null && existing.active()) return new WindowResult(existing, false);
        }
        if (!admin && user.windowsUsed >= user.windowsMax) {
            throw ApiException.conflict("no-windows", "No access windows are left for this account");
        }
        if (!admin) {
            // Cost guard over all accounts: many accounts of one person cannot keep the cluster running.
            Instant dayAgo = now.minus(Duration.ofHours(24));
            long recent = sessions.list().stream()
                    .filter(x -> x.requestedAt != null && x.requestedAt.isAfter(dayAgo)).count();
            if (recent >= settings.dailyWindowsMax()) {
                throw ApiException.conflict("daily-limit", "The demo reached its daily limit of access windows. "
                        + "Try again tomorrow.");
            }
        }
        long left = EngineRecord.COOLDOWN.equals(rec.state) ? cooldownLeft(rec, now) : 0;
        if (left > 0) throw cooldown(left);
        if (EngineRecord.STOPPING.equals(rec.state)) throw cooldown(settings.cooldown().toSeconds());

        PowerState power = cluster.powerState();
        if (power.stopping()) throw cooldown(settings.cooldown().toSeconds());

        Session s = new Session();
        s.id = ids.get();
        s.userId = user.id;
        s.requestedAt = now;
        s.add(now, Stages.REQUEST, LifecycleEvent.DONE, "browser asked for an access window", null);
        s.add(now, Stages.EDGE, LifecycleEvent.DONE, "Cloudflare edge: WAF, rate limit", null);
        s.add(now, Stages.GATEWAY, LifecycleEvent.DONE, "gateway accepted the request", null);
        s.add(now, Stages.IDENTITY, LifecycleEvent.DONE, "Entra ID token verified", identityMs);
        s.add(now, Stages.QUOTA, LifecycleEvent.DONE, admin ? "owner account"
                : "window " + (user.windowsUsed + 1) + " of " + user.windowsMax + ", engine not paused", null);
        s.add(now, Stages.KEYVAULT, LifecycleEvent.DONE, keyVaultCached ? "cached" : "read from Key Vault",
                keyVaultMs);

        if (power.running()) {
            s.add(now, Stages.ARM_START, LifecycleEvent.DONE, "cluster " + CLUSTER + " already runs", 0L);
        } else if (power.starting()) {
            s.add(now, Stages.ARM_START, LifecycleEvent.DONE, "a start of " + CLUSTER + " is in progress", 0L);
        } else {
            startCluster(s, now, user.id, ipHash);
        }

        if (!(EngineRecord.RUNNING.equals(rec.state) && rec.externalSince == null && power.running())) {
            setState(rec, EngineRecord.STARTING, now);
        }
        rec.externalSince = null;
        rec.activeSessions = rec.activeSessions + 1;
        sessions.save(s);
        engine.save(rec);
        user.activeSessionId = s.id;
        // Only this field changes, on the newest row: an admin block made meanwhile stays.
        UserRows.update(users, user.id, u -> u.activeSessionId = s.id);
        audit.record(user.id, "session.create", s.id, ipHash);
        return new WindowResult(s, true);
    }

    private void startCluster(Session s, Instant now, String who, String ipHash) {
        long t = clock.millis();
        boolean accepted = cluster.start();
        audit.record(who, accepted ? "engine.start" : "engine.start.refused", CLUSTER, ipHash);
        if (accepted) {
            s.add(now, Stages.ARM_START, LifecycleEvent.DONE, "ARM accepted the start of " + CLUSTER,
                    Math.max(0, clock.millis() - t));
        } else {
            s.addOnce(now, Stages.ARM_START, LifecycleEvent.ACTIVE, "start sent; ARM did not accept it yet");
        }
    }

    private ApiException cooldown(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return new ApiException(409, "cooldown",
                "The cluster stopped a short time ago. Try again in " + minutes + " min.", (int) seconds);
    }

    private long cooldownLeft(EngineRecord rec, Instant now) {
        if (rec.lastStopAt == null) return 0;
        return Math.max(0, Duration.between(now, rec.lastStopAt.plus(settings.cooldown())).toSeconds());
    }

    // ----------------------------------------------------- GET /sessions/{id}

    /** Returns the session when the caller owns it or is the admin. Advances a starting session. */
    public Session poll(String sessionId, String callerId, boolean admin) {
        Session s = sessions.find(sessionId).orElseThrow(ApiException::notFound);
        if (!admin && !s.userId.equals(callerId)) throw ApiException.notFound();
        Instant now = clock.instant();
        boolean due = s.lastObservedAt == null || Duration.between(s.lastObservedAt, now).compareTo(OBSERVE_EVERY) >= 0;
        if (Session.STARTING.equals(s.state) && due) {
            Signals signals = new Signals();
            boolean wasReady = advance(s, signals, now, false);
            if (wasReady) {
                EngineRecord rec = engine.load();
                if (!EngineRecord.RUNNING.equals(rec.state)) {
                    setState(rec, EngineRecord.RUNNING, now);
                    engine.save(rec);
                }
            }
            return sessions.find(sessionId).orElse(s);
        }
        return s;
    }

    /**
     * POST /api/sessions/{id}/end: the owner of the session (or the admin) ends the window early.
     * The session goes to "ended"; the timer then stops the cluster when no other window is active.
     * The window is not given back: a ready window is already counted, and a starting window is counted now
     * (it asked for a cluster start). An ended or failed session is returned unchanged.
     */
    public Session endByUser(String sessionId, String callerId, boolean admin, String ipHash) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Session s = sessions.find(sessionId).orElseThrow(ApiException::notFound);
            if (!admin && !s.userId.equals(callerId)) throw ApiException.notFound();
            if (!s.active()) return s;
            boolean countNow = Session.STARTING.equals(s.state);
            Instant now = clock.instant();
            s.add(now, Stages.WINDOW_CLOSE, LifecycleEvent.DONE,
                    admin && !s.userId.equals(callerId) ? "ended early by the owner" : "ended early by the user", null);
            s.state = Session.ENDED;
            s.endedAt = now;
            if (!save(s)) continue; // the timer or a poll changed the row: read it again
            release(s);
            if (countNow) {
                UserRows.updateQuietly(users, s.userId, u -> u.windowsUsed = u.windowsUsed + 1, "window count");
            }
            audit.record(callerId, "session.end", s.id, ipHash);
            return s;
        }
        throw ApiException.conflict("busy", "The window changed at the same time. Try again.");
    }

    public List<Session> allSessions() {
        List<Session> all = new ArrayList<>(sessions.list());
        all.sort((a, b) -> b.requestedAt.compareTo(a.requestedAt));
        return all.size() > 200 ? all.subList(0, 200) : all;
    }

    // ------------------------------------------------------------------ timer

    /** The reaper. Called each minute. */
    public void tick() {
        Instant now = clock.instant();
        Signals signals = new Signals();
        List<Session> open = sessions.listOpen();
        for (Session s : open) {
            if (Session.STARTING.equals(s.state)) {
                advance(s, signals, now, true);
            } else if (Session.READY.equals(s.state) && s.expiresAt != null && !now.isBefore(s.expiresAt)) {
                long minutes = s.readyAt == null ? settings.window().toMinutes()
                        : Duration.between(s.readyAt, s.expiresAt).toMinutes();
                end(s, now, "the " + minutes + "-minute window ended");
            }
        }
        settle(signals, now, "gateway", false);
    }

    /** Looks at the real power state and moves the engine state. Stops the cluster when nothing is active. */
    private void settle(Signals signals, Instant now, String who, boolean force) {
        EngineRecord rec = engine.load();
        List<Session> open = sessions.listOpen();
        List<Session> active = open.stream().filter(Session::active).toList();
        List<Session> waiting = open.stream().filter(s -> !s.active()).toList();
        PowerState power = signals.power();

        if (!active.isEmpty()) {
            rec.externalSince = null;
            boolean anyReady = active.stream().anyMatch(s -> Session.READY.equals(s.state));
            setState(rec, anyReady ? EngineRecord.RUNNING : EngineRecord.STARTING, now);
            if (power.stopped()) {
                // The start was lost or refused: send it again, but give ARM time to show the first one.
                Session oldest = active.stream().min((a, b) -> a.requestedAt.compareTo(b.requestedAt)).get();
                if (!signals.startSent
                        && Duration.between(oldest.requestedAt, now).compareTo(START_RETRY_AFTER) >= 0) {
                    boolean accepted = cluster.start();
                    audit.record(who, accepted ? "engine.start.retry" : "engine.start.refused", CLUSTER, null);
                }
            }
        } else if (!power.known()) {
            // ARM did not answer: change nothing. Unverified is not zero.
        } else if (power.transitioning()) {
            if (power.stopping()) setState(rec, EngineRecord.STOPPING, now);
        } else if (power.running()) {
            boolean external = !EngineRecord.RUNNING.equals(rec.state) && !EngineRecord.STARTING.equals(rec.state)
                    && !EngineRecord.STOPPING.equals(rec.state);
            if (force) {
                stopCluster(rec, waiting, now, who);
            } else if (external && rec.externalSince == null) {
                // Something else started the cluster (for example the deploy job). Give it time to finish.
                rec.externalSince = now;
                setState(rec, EngineRecord.RUNNING, now);
                audit.record(who, "engine.external-running", CLUSTER, null);
            } else if (rec.externalSince == null
                    || Duration.between(rec.externalSince, now).compareTo(settings.idleGrace()) >= 0) {
                stopCluster(rec, waiting, now, who);
            }
        } else if (power.stopped()) {
            if (EngineRecord.STOPPING.equals(rec.state) || EngineRecord.RUNNING.equals(rec.state)
                    || EngineRecord.STARTING.equals(rec.state)) {
                rec.lastStopAt = now;
                setState(rec, EngineRecord.COOLDOWN, now);
            } else if (EngineRecord.COOLDOWN.equals(rec.state) && cooldownLeft(rec, now) <= 0) {
                setState(rec, EngineRecord.STOPPED, now);
            }
            rec.externalSince = null;
            for (Session s : waiting) {
                LifecycleEvent sent = s.last(Stages.CLUSTER_STOP);
                Long ms = sent == null ? null : Math.max(0, Duration.between(Instant.parse(sent.ts()), now).toMillis());
                s.add(now, Stages.CLUSTER_STOP, LifecycleEvent.DONE, "cluster " + CLUSTER + " is stopped", ms);
                s.open = false;
                save(s);
            }
        }
        rec.activeSessions = active.size();
        if (rec.since == null) rec.since = now;
        engine.save(rec);
    }

    private void stopCluster(EngineRecord rec, List<Session> waiting, Instant now, String who) {
        boolean accepted = cluster.stop();
        audit.record(who, accepted ? "engine.stop" : "engine.stop.refused", CLUSTER, null);
        if (!accepted) return;
        rec.externalSince = null;
        setState(rec, EngineRecord.STOPPING, now);
        for (Session s : waiting) {
            if (s.last(Stages.CLUSTER_STOP) == null) {
                s.add(now, Stages.CLUSTER_STOP, LifecycleEvent.ACTIVE, "ARM accepted the stop of " + CLUSTER, null);
                save(s);
            }
        }
    }

    // ------------------------------------------------------------ admin, budget

    public void pause(boolean paused, String reason, String who, String ipHash) {
        EngineRecord rec = engine.load();
        rec.paused = paused;
        rec.pausedReason = paused ? reason : null;
        if (rec.since == null) rec.since = clock.instant();
        engine.save(rec);
        audit.record(who, paused ? "engine.pause." + reason : "engine.resume", "engine", ipHash);
    }

    /** Ends every window now and stops the cluster. */
    public void forceStop(String who, String ipHash, String reason) {
        Instant now = clock.instant();
        audit.record(who, "engine.force-stop", CLUSTER, ipHash);
        for (Session s : sessions.listOpen()) {
            if (s.active()) end(s, now, reason);
        }
        // A forced stop does not wait for the idle grace.
        settle(new Signals(), now, who, true);
    }

    /** True while new spending is paused by the budget (webhook or the timer's own cost check). */
    public boolean pausedForBudget() {
        EngineRecord rec = engine.load();
        return rec.paused && "budget".equals(rec.pausedReason);
    }

    /**
     * The timer's own budget guard, at most every 30 minutes (lastBudgetCheckAt in the engine row): it does not
     * depend on the webhook. Spend at or above the limit takes the same path as the webhook. Unknown cost: no change.
     */
    public boolean checkBudgetIfDue(dev.kmads.consignado.gateway.ports.CostReader cost) {
        Instant now = clock.instant();
        EngineRecord rec = engine.load();
        if (rec.lastBudgetCheckAt != null
                && Duration.between(rec.lastBudgetCheckAt, now).compareTo(BUDGET_CHECK_EVERY) < 0) {
            return false;
        }
        rec.lastBudgetCheckAt = now;
        engine.save(rec);
        if (rec.paused && "budget".equals(rec.pausedReason)) return false;
        if (!overBudget(cost.monthToDate().orElse(null), settings.budgetLimit(), settings.budgetCurrency())) return false;
        LOG.warning(Logs.fmt("budget guard: month-to-date cost is at or above {0} {1}; pausing",
                settings.budgetLimit(), settings.budgetCurrency()));
        audit.record("budget-guard", "budget.limit.reached", "engine", null);
        budgetExceeded();
        return true;
    }

    /**
     * The pause rule: the cost and the limit must be in the same currency. Unknown cost, no limit, or a cost in a
     * different currency (a wrong setting): no pause here; the Azure budget alert (webhook) still applies.
     */
    static boolean overBudget(dev.kmads.consignado.gateway.ports.CostReader.Cost cost, double limit, String currency) {
        if (cost == null || limit <= 0) return false;
        if (cost.currency() != null && currency != null && !cost.currency().equalsIgnoreCase(currency)) {
            LOG.warning(Logs.fmt("budget guard: cost is in {0}, the limit is in {1}; not compared", cost.currency(), currency));
            return false;
        }
        return cost.amount() >= limit;
    }

    /** The budget alert: pause with reason budget and stop the cluster now. */
    public void budgetExceeded() {
        pause(true, "budget", "budget-alert", null);
        forceStop("budget-alert", null, "stopped by the budget alert");
    }

    // ------------------------------------------------------- session lifecycle

    private final class Signals {
        private PowerState power;
        private ClusterView view;
        private Integer probeStatus;
        private long probeMs;
        private boolean startSent;
        private java.util.Optional<ClusterView.ImagePull> imagePull;

        ClusterView.ImagePull imagePull(String podName) {
            if (imagePull == null) imagePull = observer.imagePull(podName);
            return imagePull.orElse(null);
        }

        PowerState power() {
            if (power == null) power = cluster.powerState();
            return power;
        }

        ClusterView view() {
            if (view == null) view = observer.observe();
            return view;
        }

        int probe() {
            if (probeStatus == null) {
                long t = clock.millis();
                try {
                    Upstream.Response r = upstream.call(new Upstream.Request(HostKind.AKS, "GET", READINESS_PATH,
                            null, Map.of("Accept", "application/json"), null));
                    probeStatus = r.status();
                } catch (Upstream.UpstreamException e) {
                    probeStatus = 0;
                }
                probeMs = Math.max(0, clock.millis() - t);
            }
            return probeStatus;
        }
    }

    /** ms: the real duration from the pod's own times; null = the time since the stage before (as before). */
    private record Step(String stage, boolean satisfied, String doneDetail, String waitDetail, boolean error, Long ms) {
        Step(String stage, boolean satisfied, String doneDetail, String waitDetail, boolean error) {
            this(stage, satisfied, doneDetail, waitDetail, error, null);
        }
    }

    /** Milliseconds from a to b, or null when one is unknown or b is before a. */
    private static Long between(Instant a, Instant b) {
        return a == null || b == null || b.isBefore(a) ? null : Duration.between(a, b).toMillis();
    }

    /** Records what the real signals show for a starting session. Returns true when the window opened. */
    private boolean advance(Session s, Signals signals, Instant now, boolean mayStart) {
        s.lastObservedAt = now;
        if (Duration.between(s.requestedAt, now).compareTo(settings.startTimeout()) >= 0) {
            fail(s, now, "the start did not reach ready in " + settings.startTimeout().toMinutes() + " minutes");
            return false;
        }
        PowerState power = signals.power();
        if (!s.done(Stages.ARM_START)) {
            if (power.running() || power.starting()) {
                s.add(now, Stages.ARM_START, LifecycleEvent.DONE, "ARM started " + CLUSTER, since(s, now));
            } else if (power.stopped() && mayStart && !signals.startSent) {
                signals.startSent = true;
                startCluster(s, now, "gateway", null);
            }
            if (!s.done(Stages.ARM_START)) {
                save(s);
                return false;
            }
        }
        if (!power.known() || power.stopped()) {
            s.addOnce(now, Stages.CONTROL_PLANE, LifecycleEvent.ACTIVE, "waiting for ARM: power state " + power.code());
            save(s);
            return false;
        }

        ClusterView view = signals.view();
        ClusterView.Node node = view.readyNode();
        ClusterView.Pod pod = view.bestPod();
        String podName = pod == null ? "" : pod.name();
        String reason = pod == null ? null : pod.waitingReason();
        boolean pullError = "ErrImagePull".equals(reason) || "ImagePullBackOff".equals(reason)
                || "InvalidImageName".equals(reason);
        boolean startError = "CrashLoopBackOff".equals(reason) || "CreateContainerConfigError".equals(reason)
                || "CreateContainerError".equals(reason) || "RunContainerError".equals(reason);
        String podWait = pod == null ? "no pod in namespace consignado yet"
                : "pod " + podName + (reason == null ? " is " + (pod.scheduled() ? "scheduled" : "not scheduled") : ": " + reason);
        boolean podReady = pod != null && pod.ready();

        // A pod made for this start has its own times: they give the real durations of the pod stages, also
        // when one observation sees several stages done. A pod older than the request ran before: no durations.
        boolean freshPod = pod != null && pod.createdAt() != null && s.requestedAt != null
                && !pod.createdAt().isBefore(s.requestedAt.minusSeconds(5));
        ClusterView.PodTimes pt = freshPod ? pod.times() : ClusterView.PodTimes.NONE;
        Long volumesMs = between(pt.scheduled(), pt.volumesReady());
        String pullDetail = "image present for pod " + podName;
        Long pullMs = null;
        Instant pulledAt = null;
        if (freshPod && pod.imagePulled() && !s.done(Stages.IMAGE_PULL)) {
            ClusterView.ImagePull pull = signals.imagePull(podName);
            if (pull != null && pull.alreadyPresent()) {
                pullDetail = "image already on the node";
                pullMs = 0L;
            } else if (pull != null && between(pull.pullingAt(), pull.pulledAt()) != null) {
                pullMs = between(pull.pullingAt(), pull.pulledAt());
                pulledAt = pull.pulledAt();
                pullDetail = "image pulled for pod " + podName;
            } else {
                pullDetail = "image present for pod " + podName + " (pull time not readable)";
                pullMs = 0L;
            }
        }
        Long containerMs = between(pulledAt != null ? pulledAt : pt.volumesReady(), pt.containerRunning());
        Long databaseMs = between(pt.containerRunning(), pt.ready() != null ? pt.ready() : pt.containersReady());
        Long nodeMs = null;
        LifecycleEvent lastDone = lastDone(s);
        if (pt.scheduled() != null && lastDone != null) nodeMs = between(Instant.parse(lastDone.ts()), pt.scheduled());

        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Stages.CONTROL_PLANE, view.apiServerUp(), "Kubernetes API server answered",
                "waiting for the Kubernetes API server", false));
        steps.add(new Step(Stages.NODE, node != null, node == null ? "" : "node " + node.name() + " is Ready",
                view.nodes().isEmpty() ? "no node yet" : "node " + view.nodes().get(0).name() + " is not Ready", false,
                nodeMs));
        steps.add(new Step(Stages.IMAGE_PULL, pod != null && pod.imagePulled(), pullDetail, podWait, pullError, pullMs));
        steps.add(new Step(Stages.SECRETS_MOUNT, pod != null && pod.volumesMounted(),
                "volumes mounted in pod " + podName + " (CSI secret store)", podWait, false, volumesMs));
        steps.add(new Step(Stages.CONTAINER_START, pod != null && pod.containersStarted(),
                "container " + (pod == null ? "" : pod.firstContainer()) + " runs in pod " + podName, podWait,
                startError, containerMs));
        steps.add(new Step(Stages.DATABASE, podReady, "pod " + podName + " passed its readiness check (database included)",
                "pod " + podName + " starts Spring Boot and connects to PostgreSQL", false, databaseMs));
        steps.add(new Step(Stages.READINESS, podReady && view.readyEndpoints() > 0,
                view.readyEndpoints() + " ready endpoint(s) behind Service consignado-api",
                "pod " + podName + " is Ready, waiting for the Service endpoints", false));

        boolean all = true;
        for (Step step : steps) {
            if (s.done(step.stage())) continue;
            if (step.satisfied()) {
                s.add(now, step.stage(), LifecycleEvent.DONE, step.doneDetail(),
                        step.ms() != null ? step.ms() : since(s, now));
            } else {
                s.addOnce(now, step.stage(), step.error() ? LifecycleEvent.ERROR : LifecycleEvent.ACTIVE,
                        step.waitDetail());
                all = false;
                break;
            }
        }
        if (!all) {
            LifecycleEvent waitingAt = s.lastEvent();
            LOG.info(Logs.fmt("session {0}: waits at stage {1}: {2}", s.id, waitingAt.stage(), waitingAt.detail()));
            save(s);
            return false;
        }

        // The last proof is a real call through the internal load balancer.
        int status = signals.probe();
        LOG.info(Logs.fmt("session {0}: readiness call through the internal load balancer: HTTP {1} in {2} ms",
                s.id, status, signals.probeMs));
        if (status != 200) {
            s.addOnce(now, Stages.LOAD_BALANCER, LifecycleEvent.ACTIVE,
                    status == 0 ? "no answer from the internal load balancer yet" : "readiness answered HTTP " + status);
            save(s);
            return false;
        }
        if (!s.done(Stages.LOAD_BALANCER)) {
            s.add(now, Stages.LOAD_BALANCER, LifecycleEvent.DONE, "internal load balancer passed the call", since(s, now));
        }
        s.add(now, Stages.FIRST_RESPONSE, LifecycleEvent.DONE, "GET /" + READINESS_PATH + " answered 200",
                signals.probeMs);
        // The length is the owner's windowMinutes at the moment the window opens; a later change does not alter it.
        int minutes = users.find(s.userId).map(u -> u.windowMinutes(settings))
                .orElse((int) settings.window().toMinutes());
        s.state = Session.READY;
        s.readyAt = now;
        s.expiresAt = now.plus(Duration.ofMinutes(minutes));
        s.add(now, Stages.WINDOW_OPEN, LifecycleEvent.DONE, "window is open for " + minutes + " minutes", null);
        if (!save(s)) return false;
        // The window is counted only now: a start that fails costs the user nothing.
        UserRows.updateQuietly(users, s.userId, u -> u.windowsUsed = u.windowsUsed + 1, "window count");
        return true;
    }

    private static LifecycleEvent lastDone(Session s) {
        for (int i = s.events.size() - 1; i >= 0; i--) {
            if (LifecycleEvent.DONE.equals(s.events.get(i).status())) return s.events.get(i);
        }
        return null;
    }

    /** Milliseconds from the newest finished stage to now: the duration of the stage that ends now. */
    private static Long since(Session s, Instant now) {
        for (int i = s.events.size() - 1; i >= 0; i--) {
            LifecycleEvent e = s.events.get(i);
            if (LifecycleEvent.DONE.equals(e.status())) {
                return Math.max(0, Duration.between(Instant.parse(e.ts()), now).toMillis());
            }
        }
        return null;
    }

    private void fail(Session s, Instant now, String detail) {
        String stage = Stages.AKS.stream().filter(st -> !s.done(st)).findFirst().orElse(Stages.WINDOW_OPEN);
        s.add(now, stage, LifecycleEvent.ERROR, detail, null);
        s.state = Session.FAILED;
        s.endedAt = now;
        if (save(s)) release(s);
    }

    private void end(Session s, Instant now, String detail) {
        s.add(now, Stages.WINDOW_CLOSE, LifecycleEvent.DONE, detail, null);
        s.state = Session.ENDED;
        s.endedAt = now;
        if (save(s)) release(s);
    }

    private void release(Session s) {
        if (users.find(s.userId).map(u -> s.id.equals(u.activeSessionId)).orElse(false)) {
            UserRows.updateQuietly(users, s.userId, u -> {
                if (s.id.equals(u.activeSessionId)) u.activeSessionId = null;
            }, "window release");
        }
    }

    /** False when another writer changed the row first: this writer drops its change. */
    private boolean save(Session s) {
        try {
            sessions.save(s);
            return true;
        } catch (SessionStore.ConflictException e) {
            return false;
        }
    }

    private static void setState(EngineRecord rec, String state, Instant now) {
        if (!state.equals(rec.state) || rec.since == null) {
            rec.state = state;
            rec.since = now;
        }
    }
}
