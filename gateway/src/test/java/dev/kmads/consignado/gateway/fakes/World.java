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

    public static final class TestClock extends Clock {
        public Instant now = Instant.parse("2026-11-17T12:00:00Z");
        public void plus(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    public static final class Users implements UserStore {
        public final Map<String, User> rows = new LinkedHashMap<>();
        @Override public Optional<User> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public void save(User user) { rows.put(user.id, user); }
        @Override public List<User> list() { return new ArrayList<>(rows.values()); }
    }

    public static final class Sessions implements SessionStore {
        private final Map<String, Session> rows = new LinkedHashMap<>();
        private int version;
        private static Session copy(Session s) {
            Session c = new Session();
            c.id = s.id; c.userId = s.userId; c.state = s.state; c.requestedAt = s.requestedAt;
            c.readyAt = s.readyAt; c.expiresAt = s.expiresAt; c.endedAt = s.endedAt;
            c.lastObservedAt = s.lastObservedAt; c.events = new ArrayList<>(s.events); c.open = s.open; c.etag = s.etag;
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
    }

    public static final class Cluster implements ClusterControl, ClusterObserver {
        public PowerState power = new PowerState("Stopped", "Succeeded");
        public ClusterView view = ClusterView.DOWN;
        public boolean accept = true;
        public int starts;
        public int stops;
        @Override public PowerState powerState() { return power; }
        @Override public boolean start() { starts++; if (accept) power = new PowerState("Running", "Starting"); return accept; }
        @Override public boolean stop() { stops++; if (accept) power = new PowerState("Running", "Stopping"); return accept; }
        @Override public ClusterView observe() { return view; }
        public void running() { power = new PowerState("Running", "Succeeded"); }
        public void stopped() { power = new PowerState("Stopped", "Succeeded"); }
    }

    public static final class Up implements Upstream {
        public final List<Request> calls = new ArrayList<>();
        public int status = 200;
        public byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        public int readiness = 503;
        public UpstreamException fail;
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
            return new Response(status, "application/json", fixed != null ? fixed.getBytes(StandardCharsets.UTF_8) : body);
        }
        public Request last() { return calls.get(calls.size() - 1); }
    }

    public static final class Vault implements Secrets {
        public final Map<String, String> values = new HashMap<>();
        private final Set<String> seen = new HashSet<>();
        public int creates;
        @Override public Read read(String name) { return new Read(values.get(name), !seen.add(name)); }
        @Override public String readOrCreate(String name, Supplier<String> generator) {
            if (!values.containsKey(name)) { creates++; values.put(name, generator.get()); }
            return values.get(name);
        }
    }

    public final TestClock clock = new TestClock();
    public final Users users = new Users();
    public final Sessions sessions = new Sessions();
    public final Cluster cluster = new Cluster();
    public final Up upstream = new Up();
    public final Vault vault = new Vault();
    public final List<String> audit = new ArrayList<>();
    public final List<String> mails = new ArrayList<>();
    public final List<Contact> contactRows = new ArrayList<>();
    public final Map<String, String> blob = new HashMap<>();
    public boolean botOk = true;
    public Optional<CostReader.Cost> cost = Optional.empty();
    public long nanos;
    public EngineRecord engineRow = new EngineRecord();
    public final Settings settings = Settings.defaults(ADMIN);
    public final EngineService engine;
    public final SnapshotService snapshot;
    public final Handlers handlers;
    public final AtomicInteger ids = new AtomicInteger();

    public World() { this(false); }

    public World(boolean allowDevOrigin) {
        vault.values.put(ProxyService.HOST_KEY, "host-key-value");
        AuditLog auditLog = (who, action, target, ipHash) -> audit.add(action);
        Mailer mailer = (subject, text) -> mails.add(subject);
        BotCheck bot = (token, ip) -> botOk;
        EngineStore engineStore = new EngineStore() {
            @Override public EngineRecord load() {
                EngineRecord c = new EngineRecord();
                c.state = engineRow.state; c.since = engineRow.since; c.paused = engineRow.paused;
                c.pausedReason = engineRow.pausedReason; c.lastStopAt = engineRow.lastStopAt;
                c.activeSessions = engineRow.activeSessions; c.externalSince = engineRow.externalSince;
                c.lastSnapshotAt = engineRow.lastSnapshotAt;
                return c;
            }
            @Override public void save(EngineRecord record) { engineRow = record; }
        };
        ContactStore contactStore = new ContactStore() {
            @Override public void save(Contact contact) { contactRows.add(contact); }
            @Override public List<Contact> list() { return List.copyOf(contactRows); }
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
        AdminService admin = new AdminService(users, engine, () -> cost, auditLog, settings, clock);
        snapshot = new SnapshotService(upstream, vault, snapshotStore, engineStore, clock);
        handlers = new Handlers(verifier, userService, engine, proxy, contacts, admin, snapshot, vault, hasher,
                auditLog, settings, allowDevOrigin, () -> nanos, new RateGuard(5, Duration.ofMinutes(10), clock),
                new RateGuard(3, Duration.ofMinutes(10), clock));
    }

    // ------------------------------------------------------------ request helpers

    public static GatewayRequest req(String method, String pathParam, String token, String ip, String body) {
        Map<String, String> headers = new HashMap<>();
        if (token != null) headers.put("Authorization", "Bearer " + token);
        if (ip != null) headers.put("X-Azure-ClientIP", ip);
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
