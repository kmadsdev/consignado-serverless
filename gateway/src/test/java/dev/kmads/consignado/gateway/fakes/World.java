package dev.kmads.consignado.gateway.fakes;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.*;
import dev.kmads.consignado.gateway.http.GatewayRequest;
import dev.kmads.consignado.gateway.http.GatewayResponse;
import dev.kmads.consignado.gateway.http.Handlers;
import dev.kmads.consignado.gateway.ports.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Every port as an in-memory fake, wired to the real core and the real handlers. No network. */
public final class World {
    public static final String ADMIN = "00000000-0000-0000-0000-00000000ad00";
    private static final java.util.concurrent.ExecutorService STATUS_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "status-test");
                t.setDaemon(true);
                return t;
            });

    public static final class TestClock extends Clock {
        public Instant now = Instant.parse("2026-11-17T12:00:00Z");
        public void plus(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Users with copies and ETags, like the table: a stale write gets ConflictException. */
    public static final class Users implements UserStore {
        public final Map<String, User> rows = new LinkedHashMap<>();
        private int version;
        /** Runs before each save: a test can make another writer change the row first. */
        public Runnable beforeSave = () -> { };
        public static User copy(User u) {
            User c = new User();
            c.id = u.id; c.email = u.email; c.name = u.name; c.company = u.company; c.linkedin = u.linkedin;
            c.status = u.status; c.windowsUsed = u.windowsUsed; c.windowsMax = u.windowsMax; c.windowMinutes = u.windowMinutes;
            c.flags = new TreeSet<>(u.flags); c.ipHash = u.ipHash; c.fingerprintHash = u.fingerprintHash;
            c.createdAt = u.createdAt; c.lastSeenAt = u.lastSeenAt; c.consentAt = u.consentAt;
            c.approvedByAdmin = u.approvedByAdmin; c.activeSessionId = u.activeSessionId;
            c.mailboxHash = u.mailboxHash; c.etag = u.etag;
            return c;
        }
        @Override public Optional<User> find(String id) { return Optional.ofNullable(rows.get(id)).map(Users::copy); }
        @Override public void save(User user) {
            Runnable hook = beforeSave;
            beforeSave = () -> { };
            hook.run();
            User stored = rows.get(user.id);
            if (user.etag != null && stored != null && !user.etag.equals(stored.etag)) throw new ConflictException();
            user.etag = "u" + (++version);
            rows.put(user.id, copy(user));
        }
        @Override public List<User> list() { return rows.values().stream().map(Users::copy).toList(); }
        @Override public void delete(String id) { rows.remove(id); }
    }

    public static final class Sessions implements SessionStore {
        private final Map<String, Session> rows = new LinkedHashMap<>();
        private int version;
        private static Session copy(Session s) {
            Session c = new Session();
            c.id = s.id; c.userId = s.userId; c.state = s.state; c.requestedAt = s.requestedAt;
            c.readyAt = s.readyAt; c.expiresAt = s.expiresAt; c.endedAt = s.endedAt;
            c.lastObservedAt = s.lastObservedAt; c.events = new ArrayList<>(s.events); c.open = s.open; c.etag = s.etag;
            c.requests = s.requests;
            return c;
        }
        @Override public Optional<Session> find(String id) { return Optional.ofNullable(rows.get(id)).map(Sessions::copy); }
        @Override public void save(Session s) {
            Session stored = rows.get(s.id);
            if (s.etag != null && stored != null && !s.etag.equals(stored.etag)) throw new ConflictException();
            s.etag = "v" + (++version);
            rows.put(s.id, copy(s));
        }
        @Override public List<Session> listOpen() { return rows.values().stream().filter(s -> s.open).map(Sessions::copy).toList(); }
        @Override public List<Session> list() { return rows.values().stream().map(Sessions::copy).toList(); }
        @Override public void delete(String id) { rows.remove(id); }
        public boolean has(String id) { return rows.containsKey(id); }
    }

    public static final class Cluster implements ClusterControl, ClusterObserver {
        public PowerState power = new PowerState("Stopped", "Succeeded");
        public ClusterView view = ClusterView.DOWN;
        public boolean accept = true;
        public int starts;
        public int stops;
        public int observations;
        public Optional<ClusterView.Workload> workload = Optional.empty();
        public Optional<ClusterView.ImagePull> imagePull = Optional.empty();
        public int imagePullReads;
        @Override public Optional<ClusterView.ImagePull> imagePull(String pod) { imagePullReads++; return imagePull; }
        @Override public Optional<ClusterView.Workload> workload() { return workload; }
        @Override public PowerState powerState() { return power; }
        @Override public boolean start() { starts++; if (accept) power = new PowerState("Running", "Starting"); return accept; }
        @Override public boolean stop() { stops++; if (accept) power = new PowerState("Running", "Stopping"); return accept; }
        @Override public ClusterView observe() { observations++; return view; }
        public void running() { power = new PowerState("Running", "Succeeded"); }
        public void stopped() { power = new PowerState("Stopped", "Succeeded"); }
    }

    public static final class Up implements Upstream {
        public final List<Request> calls = new ArrayList<>();
        public int status = 200;
        public byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        public int readiness = 503;
        public UpstreamException fail;
        /** Raw X-Consignado-Timing of the answers (null = the API host sent none). */
        public String timing;
        public Runnable onCall = () -> { };
        public final Map<String, String> bodies = new HashMap<>();
        @Override public Response call(Request request) throws UpstreamException {
            calls.add(request);
            onCall.run();
            if (fail != null) throw fail;
            if (request.path().equals(EngineService.READINESS_PATH)) {
                if (readiness == 0) throw new UpstreamException(false);
                return new Response(readiness, "application/json", new byte[0]);
            }
            String fixed = bodies.get(request.path());
            return new Response(status, "application/json", fixed != null ? fixed.getBytes(StandardCharsets.UTF_8) : body,
                    timing);
        }
        public Request last() { return calls.get(calls.size() - 1); }
    }

    public static final class Vault implements Secrets {
        public final Map<String, String> values = new HashMap<>();
        private final Set<String> seen = new HashSet<>();
        /** Key Vault does not answer. */
        public boolean down;
        @Override public Read read(String name) {
            if (down) throw new IllegalStateException("Key Vault down");
            return new Read(values.get(name), !seen.add(name));
        }
    }

    /** Azure Monitor and ARM answers by resource id; a missing entry = Azure did not answer. */
    public static final class Monitor implements AzureMonitor {
        public final Map<String, JsonNode> metrics = new HashMap<>();
        public final Map<String, JsonNode> health = new HashMap<>();
        public final Map<String, JsonNode> resources = new HashMap<>();
        public final Set<String> slow = new HashSet<>();
        public final AtomicInteger calls = new AtomicInteger();
        private Optional<JsonNode> answer(Map<String, JsonNode> m, String id) {
            calls.incrementAndGet();
            if (slow.contains(id)) {
                try { Thread.sleep(6000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Optional.ofNullable(m.get(id));
        }
        @Override public Optional<JsonNode> metrics(String id, List<String> names) { return answer(metrics, id); }
        @Override public Optional<JsonNode> health(String id) { return answer(health, id); }
        @Override public Optional<JsonNode> requestMetrics(String id) { return answer(metrics, id); }
        @Override public Optional<JsonNode> resource(String id) { return answer(resources, id); }
    }

    public static final String SUB = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-consignado/providers/";
    public static final StatusService.Ids IDS = new StatusService.Ids(
            SUB + "Microsoft.Web/sites/func-consignado-gw-km01", SUB + "Microsoft.Web/sites/func-consignado-api-km01",
            SUB + "Microsoft.KeyVault/vaults/kv-consig-km01",
            SUB + "Microsoft.DBforPostgreSQL/flexibleServers/psql-consignado-km01",
            SUB + "Microsoft.ContainerService/managedClusters/aks-consignado",
            SUB + "Microsoft.Insights/components/appi-consignado");

    public final Monitor monitor = new Monitor();
    public Instant keysFetchedAt = Instant.parse("2026-11-17T11:57:00Z");

    public final TestClock clock = new TestClock();
    public final Users users = new Users();
    public final Sessions sessions = new Sessions();
    public final Cluster cluster = new Cluster();
    public final Up upstream = new Up();
    public final Vault vault = new Vault();
    public final List<String> audit = new ArrayList<>();
    /** Full audit rows: who, action, target, ipHash. */
    public final List<String[]> auditRows = new ArrayList<>();
    /** Days of the fake audit table (the partition keys). */
    public final List<java.time.LocalDate> auditDays = new ArrayList<>();
    public final RetentionService retention;
    public final List<String> mails = new ArrayList<>();
    public final List<Contact> contactRows = new ArrayList<>();
    public final Map<String, String> blob = new HashMap<>();
    public boolean botOk = true;
    public boolean mailFails;
    public Optional<CostReader.Cost> cost = Optional.empty();
    public long nanos;
    public EngineRecord engineRow = new EngineRecord();
    public final Settings settings = Settings.defaults(ADMIN);
    public final EngineService engine;
    public final SnapshotService snapshot;
    public final Handlers handlers;
    public final AtomicInteger ids = new AtomicInteger();

    public World() { this(false, false); }

    public World(boolean allowDevOrigin) { this(allowDevOrigin, false); }

    public World(boolean allowDevOrigin, boolean edgeKeyRequired) {
        vault.values.put(ProxyService.HOST_KEY, "host-key-value");
        vault.values.put(Hasher.KEY_NAME, "test-hash-key");
        AuditLog auditLog = (who, action, target, ipHash) -> {
            audit.add(action);
            auditRows.add(new String[] {who, action, target, ipHash});
        };
        AuditStore auditStore = day -> {
            int before = auditDays.size();
            auditDays.removeIf(d -> d.isBefore(day));
            return before - auditDays.size();
        };
        // The real guard around a fake sender: mailFails makes the send throw, as a refused ACS call does.
        Mailer mailer = new GuardedMailer((subject, text) -> {
            if (mailFails) throw new IllegalStateException("ACS refused the mail for owner@example.com");
            mails.add(subject);
        }, auditLog);
        BotCheck bot = (token, ip) -> botOk;
        EngineStore engineStore = new EngineStore() {
            @Override public EngineRecord load() {
                EngineRecord c = new EngineRecord();
                c.state = engineRow.state; c.since = engineRow.since; c.paused = engineRow.paused;
                c.pausedReason = engineRow.pausedReason; c.lastStopAt = engineRow.lastStopAt;
                c.activeSessions = engineRow.activeSessions; c.externalSince = engineRow.externalSince;
                c.lastSnapshotAt = engineRow.lastSnapshotAt; c.lastRetentionAt = engineRow.lastRetentionAt;
                c.lastBudgetCheckAt = engineRow.lastBudgetCheckAt;
                return c;
            }
            @Override public void save(EngineRecord record) { engineRow = record; }
        };
        ContactStore contactStore = new ContactStore() {
            @Override public void save(Contact contact) { contactRows.add(contact); }
            @Override public List<Contact> list() { return List.copyOf(contactRows); }
            @Override public void delete(String id) { contactRows.removeIf(c -> c.id().equals(id)); }
        };
        SnapshotStore snapshotStore = new SnapshotStore() {
            @Override public Optional<String> read() { return Optional.ofNullable(blob.get("snapshot")); }
            @Override public void write(String json) { blob.put("snapshot", json); }
        };
        TokenVerifier verifier = token -> {
            if (!token.startsWith("good-")) throw new AuthException("bad");
            String oid = token.substring(5);
            return new Claims(oid, oid + "@example.com", "User " + oid, Set.of("access_as_user"));
        };
        Supplier<String> idSupplier = () -> "sess" + String.format("%04d", ids.incrementAndGet());
        Hasher hasher = new Hasher(vault);
        UserService userService = new UserService(users, mailer, bot, hasher, auditLog, settings, clock);
        engine = new EngineService(users, sessions, engineStore, cluster, cluster, upstream, auditLog, settings, clock,
                idSupplier);
        ProxyService proxy = new ProxyService(upstream, vault, sessions, clock, () -> nanos);
        ContactService contacts = new ContactService(contactStore, mailer, bot, hasher, auditLog, clock, idSupplier);
        UserErasure erasure = new UserErasure(users, sessions, contactStore);
        AdminService admin = new AdminService(users, engine, () -> cost, auditLog, settings, clock, erasure, hasher);
        retention = new RetentionService(users, sessions, erasure, auditStore, auditLog, engineStore, hasher, settings,
                clock);
        snapshot = new SnapshotService(upstream, vault, snapshotStore, engineStore, clock);
        handlers = new Handlers(verifier, userService, engine, proxy, contacts, admin, snapshot, vault, hasher,
                auditLog, settings, allowDevOrigin, () -> nanos, new RateGuard(5, Duration.ofMinutes(10), clock),
                new RateGuard(3, Duration.ofMinutes(10), clock), edgeKeyRequired, retention,
                new StatusService(monitor, cluster, cluster, upstream, () -> {
                    if (keysFetchedAt == null) throw new IllegalStateException("no keys");
                    return keysFetchedAt;
                }, IDS, clock, STATUS_POOL));
    }

    // ------------------------------------------------------------ request helpers

    public static GatewayRequest req(String method, String pathParam, String token, String ip, String body) {
        Map<String, String> headers = new HashMap<>();
        if (token != null) headers.put("Authorization", "Bearer " + token);
        if (ip != null) headers.put("CF-Connecting-IP", ip);
        return GatewayRequest.of(method, pathParam, null, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    public static JsonNode json(GatewayResponse response) {
        try {
            return Json.MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON");
        }
    }

    public static String profileBody(String fingerprint) {
        return "{\"name\":\"Ana Souza\",\"company\":\"Acme Ltda\",\"linkedin\":\"https://www.linkedin.com/in/ana-souza\","
                + "\"consent\":true,\"fingerprint\":\"" + fingerprint + "\",\"turnstileToken\":\"tok\"}";
    }

    /** Signs in a user and completes the profile. */
    public GatewayResponse signUp(String oid, String ip, String fingerprint) {
        handlers.me(req("GET", null, "good-" + oid, ip, null));
        return handlers.profile(req("PUT", null, "good-" + oid, ip, profileBody(fingerprint)));
    }

    public GatewayResponse createSession(String oid) {
        return handlers.sessionsCreate(req("POST", null, "good-" + oid, "203.0.113.9", null));
    }

    /** A cluster view where one pod is Ready behind the Service. */
    public static ClusterView readyView() {
        ClusterView.Container c = new ClusterView.Container("consignado-api", "running", null, true);
        ClusterView.Pod pod = new ClusterView.Pod("consignado-api-7d9f-abcde", "aks-nodepool1-123-vmss000000", "Running",
                true, true, true, true, true, List.of(c));
        return new ClusterView(true, List.of(new ClusterView.Node("aks-nodepool1-123-vmss000000", true)), List.of(pod), 1);
    }

    /** Brings the cluster and the workload to ready and opens the window of the session. */
    public void makeReady() {
        cluster.running();
        cluster.view = readyView();
        upstream.readiness = 200;
        engine.tick();
    }
}
