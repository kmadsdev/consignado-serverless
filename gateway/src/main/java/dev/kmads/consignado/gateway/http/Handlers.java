package dev.kmads.consignado.gateway.http;

import dev.kmads.consignado.gateway.core.Logs;
import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.AdminService;
import dev.kmads.consignado.gateway.core.ApiException;
import dev.kmads.consignado.gateway.core.AuthException;
import dev.kmads.consignado.gateway.core.Claims;
import dev.kmads.consignado.gateway.core.ContactService;
import dev.kmads.consignado.gateway.core.EngineService;
import dev.kmads.consignado.gateway.core.Hasher;
import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.core.ProxyService;
import dev.kmads.consignado.gateway.core.RateGuard;
import dev.kmads.consignado.gateway.core.RetentionService;
import dev.kmads.consignado.gateway.core.StatusService;
import dev.kmads.consignado.gateway.core.Session;
import dev.kmads.consignado.gateway.core.Settings;
import dev.kmads.consignado.gateway.core.SnapshotService;
import dev.kmads.consignado.gateway.core.TraceBuilder;
import dev.kmads.consignado.gateway.core.User;
import dev.kmads.consignado.gateway.core.UserService;
import dev.kmads.consignado.gateway.core.Validate;
import dev.kmads.consignado.gateway.core.Views;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.Secrets;
import dev.kmads.consignado.gateway.ports.TokenVerifier;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Every route of the gateway, without Azure types. The function classes only convert the
 * request and the response. All token checks, errors and response headers are here.
 */
public final class Handlers {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    public static final String DEV_ORIGIN = "http://localhost:5173";
    public static final String EDGE_KEY = "edge-key";
    public static final String EXPOSED = "x-consignado-host, x-consignado-trace";
    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final int MAX_TOKEN_CHARS = 8192;
    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{3,64}$");

    private record Caller(Claims claims, long identityMs) {
        boolean guest() { return claims == null; }
    }

    private final TokenVerifier verifier;
    private final UserService users;
    private final EngineService engine;
    private final ProxyService proxy;
    private final ContactService contacts;
    private final AdminService admin;
    private final SnapshotService snapshot;
    private final Secrets secrets;
    private final Hasher hasher;
    private final AuditLog audit;
    private final Settings settings;
    private final boolean allowDevOrigin;
    private final LongSupplier nanos;
    private final RateGuard sessionGuard;
    private final RateGuard contactGuard;
    private final boolean edgeKeyRequired;
    private final RetentionService retention;
    private final StatusService status;

    public Handlers(TokenVerifier verifier, UserService users, EngineService engine, ProxyService proxy,
                    ContactService contacts, AdminService admin, SnapshotService snapshot, Secrets secrets,
                    Hasher hasher, AuditLog audit, Settings settings, boolean allowDevOrigin, LongSupplier nanos,
                    RateGuard sessionGuard, RateGuard contactGuard, boolean edgeKeyRequired,
                    RetentionService retention, StatusService status) {
        this.retention = retention;
        this.status = status;
        this.verifier = verifier;
        this.users = users;
        this.engine = engine;
        this.proxy = proxy;
        this.contacts = contacts;
        this.admin = admin;
        this.snapshot = snapshot;
        this.secrets = secrets;
        this.hasher = hasher;
        this.audit = audit;
        this.settings = settings;
        this.allowDevOrigin = allowDevOrigin;
        this.nanos = nanos;
        this.sessionGuard = sessionGuard;
        this.contactGuard = contactGuard;
        this.edgeKeyRequired = edgeKeyRequired;
    }

    // ----------------------------------------------------------------- public

    public GatewayResponse health(GatewayRequest req) {
        return run(req, () -> GatewayResponse.json(200, Map.of("status", "UP")));
    }

    public GatewayResponse engine(GatewayRequest req) {
        return run(req, () -> GatewayResponse.json(200, engine.view()));
    }

    public GatewayResponse snapshot(GatewayRequest req) {
        return run(req, () -> GatewayResponse.raw(200, "application/json",
                snapshot.current().getBytes(StandardCharsets.UTF_8)));
    }

    public GatewayResponse contact(GatewayRequest req) {
        return run(req, () -> {
            Caller caller = authenticate(req);
            String ip = clientIp(req);
            contactGuard.check(caller.guest() ? "ip:" + hasher.hash(ip == null ? "unknown" : ip) : caller.claims().oid());
            return GatewayResponse.json(201,
                    contacts.create(json(req), ip, caller.guest() ? null : caller.claims().oid()));
        });
    }

    /** GET /api/status?host=functions|aks: anyone for functions, a signed-in user for aks. Cached 15 s per host. */
    public GatewayResponse status(GatewayRequest req) {
        return run(req, () -> {
            String host = queryValue(req.rawQuery(), "host");
            HostKind kind;
            if ("functions".equals(host)) {
                kind = HostKind.FUNCTIONS;
            } else if ("aks".equals(host)) {
                requireUser(req);
                kind = HostKind.AKS;
            } else {
                throw ApiException.badRequest("Query host must be functions or aks");
            }
            return GatewayResponse.json(200, status.status(kind));
        });
    }

    private static String queryValue(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) return part.substring(eq + 1);
        }
        return null;
    }

    // ------------------------------------------------------------------- user

    public GatewayResponse me(GatewayRequest req) {
        return run(req, () -> {
            Caller caller = requireUser(req);
            return GatewayResponse.json(200, Views.me(users.me(caller.claims(), clientIp(req)), settings));
        });
    }

    public GatewayResponse profile(GatewayRequest req) {
        return run(req, () -> {
            Caller caller = requireUser(req);
            return GatewayResponse.json(200,
                    Views.me(users.updateProfile(caller.claims(), json(req), clientIp(req)), settings));
        });
    }

    public GatewayResponse sessionsCreate(GatewayRequest req) {
        return run(req, () -> {
            Caller caller = requireUser(req);
            String ip = clientIp(req);
            User user = users.me(caller.claims(), ip);
            sessionGuard.check(user.id);
            // The gateway reads its secrets here; the time is the Key Vault stage of the window.
            long t = nanos.getAsLong();
            boolean cached;
            try {
                cached = secrets.read(ProxyService.HOST_KEY).cached();
            } catch (RuntimeException e) {
                cached = false;
            }
            long kvMs = cached ? 0 : ms(t);
            EngineService.WindowResult result = engine.requestWindow(user, users.isAdmin(caller.claims()),
                    caller.identityMs(), kvMs, cached, hasher.hash(ip));
            return GatewayResponse.json(result.created() ? 201 : 200, Views.session(result.session()));
        });
    }

    public GatewayResponse sessionsGet(GatewayRequest req) {
        return run(req, () -> {
            Caller caller = requireUser(req);
            Session s = engine.poll(Validate.sessionId(req.pathParam()), caller.claims().oid(),
                    users.isAdmin(caller.claims()));
            return GatewayResponse.json(200, Views.session(s));
        });
    }

    /** POST /api/sessions/{id}/end: the owner of the window (or the admin) ends it early. */
    public GatewayResponse sessionsEnd(GatewayRequest req) {
        return run(req, () -> {
            Caller caller = requireUser(req);
            Session s = engine.endByUser(Validate.sessionId(req.pathParam()), caller.claims().oid(),
                    users.isAdmin(caller.claims()), hasher.hash(clientIp(req)));
            return GatewayResponse.json(200, Views.session(s));
        });
    }

    // ------------------------------------------------------------------ proxy

    public GatewayResponse proxy(HostKind host, GatewayRequest req) {
        long start = nanos.getAsLong();
        return run(req, () -> {
            Caller caller = authenticate(req);
            if (caller.guest() && engine.pausedForBudget()) {
                // Anonymous load must not keep spending after the budget is reached.
                throw new ApiException(503, "paused", "The demo is paused because its monthly budget is used");
            }
            User user = caller.guest() ? null : users.me(caller.claims(), clientIp(req));
            ProxyService.Result r = proxy.forward(host, req.method(), req.pathParam(), req.rawQuery(), req.headers(),
                    req.body(), user, !caller.guest() && users.isAdmin(caller.claims()), caller.identityMs(), start);
            GatewayResponse response = GatewayResponse.raw(r.status(), r.contentType(), r.body());
            response.headers().put("x-consignado-host", r.host().id());
            response.headers().put("x-consignado-trace", TraceBuilder.encode(r.trace()));
            response.headers().put("Access-Control-Expose-Headers", EXPOSED);
            return response;
        });
    }

    // ------------------------------------------------------------------ admin

    public GatewayResponse admin(GatewayRequest req) {
        return run(req, () -> {
            Caller caller = requireUser(req);
            if (!users.isAdmin(caller.claims())) throw ApiException.forbidden();
            String adminId = caller.claims().oid();
            String ipHash = hasher.hash(clientIp(req));
            String path = req.pathParam() == null ? "" : req.pathParam().replaceAll("^/+|/+$", "");
            String key = req.method() + " " + path;
            if (path.startsWith("users/") && req.method().equals("POST")) {
                return GatewayResponse.json(200, admin.act(adminId, path.substring("users/".length()), json(req), ipHash));
            }
            GatewayResponse response = switch (key) {
                case "GET users" -> GatewayResponse.json(200, admin.listUsers());
                case "GET sessions" -> GatewayResponse.json(200,
                        engine.allSessions().stream().map(Views::session).toList());
                case "GET state" -> GatewayResponse.json(200, admin.state());
                case "GET contacts" -> GatewayResponse.json(200, contacts.list());
                case "POST pause" -> GatewayResponse.json(200, admin.pause(adminId, json(req), ipHash));
                case "POST engine/stop" -> GatewayResponse.json(200, admin.stopEngine(adminId, ipHash));
                default -> throw ApiException.notFound();
            };
            if (req.method().equals("GET")) audit.record(adminId, "admin.read", path, ipHash);
            return response;
        });
    }

    /**
     * The budget action group calls this. The Functions host checks the function key before this code runs.
     * A body that shows a spend below the budget (an early alert) changes nothing; every other body pauses.
     */
    public GatewayResponse budgetHook(GatewayRequest req) {
        return run(req, false, () -> {
            Boolean below = belowBudget(req.body());
            if (below == null) {
                // A body that cannot be read does not pause any more (L3). The timer's own cost check stays.
                audit.record("budget-alert", "budget.alert.unreadable", "engine", null);
                throw ApiException.badRequest("The body is not a budget alert");
            }
            if (below) {
                audit.record("budget-alert", "budget.alert.early", "engine", null);
                return GatewayResponse.json(200, Map.of("status", "noted"));
            }
            engine.budgetExceeded();
            return GatewayResponse.json(200, Map.of("status", "paused"));
        });
    }

    /** The timer: sessions and cluster first, then the snapshot, then the daily retention job. */
    public void reaper() {
        try {
            engine.tick();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, Logs.fmt("reaper: engine tick failed: {0}", Logs.of(e)));
        }
        try {
            admin.checkBudgetIfDue();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, Logs.fmt("reaper: budget check failed: {0}", Logs.of(e)));
        }
        try {
            snapshot.refreshIfDue();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, Logs.fmt("reaper: snapshot refresh failed: {0}", Logs.of(e)));
        }
        try {
            retention.runIfDue();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, Logs.fmt("reaper: retention failed: {0}", Logs.of(e)));
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * True: an early alert. False: at or above the budget. Null: not a budget alert body.
     * SpendingAmount and Budget are both in the billing currency of the subscription (BRL), so they compare directly.
     */
    private static Boolean belowBudget(byte[] body) {
        try {
            JsonNode data = Json.MAPPER.readTree(body).path("data");
            JsonNode spent = data.path("SpendingAmount");
            JsonNode budget = data.path("Budget");
            if (spent.isMissingNode() || budget.isMissingNode()) return null;
            double s = Double.parseDouble(spent.asText());
            double b = Double.parseDouble(budget.asText());
            if (Double.isNaN(s) || Double.isNaN(b)) return null;
            return b > 0 && s < b;
        } catch (Exception e) {
            return null;
        }
    }

    private GatewayResponse run(GatewayRequest req, Supplier<GatewayResponse> action) {
        return run(req, true, action);
    }

    /**
     * The edge key proves that the request came through the Cloudflare Worker. It is checked before
     * anything else, and a refusal says nothing about the reason.
     */
    private enum Edge { OK, REFUSED, UNAVAILABLE }

    /** The secret that cannot be read (Key Vault error, or no value) gives 503: never a pass. */
    private Edge edgeKey(GatewayRequest req) {
        String expected;
        try {
            expected = secrets.read(EDGE_KEY).value();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, Logs.fmt("edge key could not be read: {0}", e.getClass().getSimpleName()));
            return Edge.UNAVAILABLE;
        }
        if (expected == null || expected.isEmpty()) {
            LOG.log(Level.WARNING, "edge key: the secret edge-key has no value");
            return Edge.UNAVAILABLE;
        }
        String sent = req.header("x-edge-key");
        if (sent == null || sent.isEmpty() || sent.length() > 512) return Edge.REFUSED;
        return java.security.MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)) ? Edge.OK : Edge.REFUSED;
    }

    private GatewayResponse run(GatewayRequest req, boolean edgeRoute, Supplier<GatewayResponse> action) {
        GatewayResponse response;
        Edge edge = edgeKeyRequired && edgeRoute ? edgeKey(req) : Edge.OK;
        if (edge != Edge.OK) {
            response = edge == Edge.UNAVAILABLE
                    ? GatewayResponse.json(503, new Views.ErrorBody("unavailable", "Service unavailable"))
                    : GatewayResponse.json(403, new Views.ErrorBody("forbidden", "Not allowed"));
            response.headers().put("Cache-Control", "no-store");
            response.headers().put("X-Content-Type-Options", "nosniff");
            return response;
        }
        if (req.method().equals("OPTIONS")) {
            response = new GatewayResponse(204, new java.util.LinkedHashMap<>(), new byte[0]);
        } else {
            try {
                response = action.get();
            } catch (ApiException e) {
                response = GatewayResponse.json(e.status(), new Views.ErrorBody(e.code(), e.getMessage()));
                if (e.retryAfterSeconds() != null) {
                    response.headers().put("Retry-After", String.valueOf(e.retryAfterSeconds()));
                }
            } catch (RuntimeException e) {
                // The log gets the class and the message. The response gets neither.
                LOG.log(Level.SEVERE, Logs.fmt("request failed: {0}", Logs.of(e)));
                response = GatewayResponse.json(500, new Views.ErrorBody("internal", "Internal error"));
            }
        }
        response.headers().put("Cache-Control", "no-store");
        response.headers().put("X-Content-Type-Options", "nosniff");
        if (allowDevOrigin && DEV_ORIGIN.equals(req.header("origin"))) {
            response.headers().put("Access-Control-Allow-Origin", DEV_ORIGIN);
            response.headers().put("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, OPTIONS");
            response.headers().put("Access-Control-Allow-Headers", "authorization, content-type");
            response.headers().put("Access-Control-Expose-Headers", EXPOSED);
            response.headers().put("Access-Control-Max-Age", "600");
            response.headers().put("Vary", "Origin");
        }
        return response;
    }

    /** No Authorization header: guest. A header that does not verify: 401, never a silent guest. */
    private Caller authenticate(GatewayRequest req) {
        String header = req.header("authorization");
        if (header == null || header.isBlank()) return new Caller(null, 0);
        if (header.length() > MAX_TOKEN_CHARS || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw invalidToken();
        }
        long t = nanos.getAsLong();
        try {
            Claims claims = verifier.verify(header.substring(7).strip());
            return new Caller(claims, ms(t));
        } catch (AuthException e) {
            LOG.log(Level.INFO, Logs.fmt("token refused: {0}", e.getMessage()));
            throw invalidToken();
        }
    }

    private static ApiException invalidToken() {
        return new ApiException(401, "invalid-token", "The token is not valid");
    }

    private Caller requireUser(GatewayRequest req) {
        Caller caller = authenticate(req);
        if (caller.guest()) throw ApiException.unauthorized();
        return caller;
    }

    /**
     * The Cloudflare edge sets CF-Connecting-IP. It is read only inside a route action, and an action
     * runs only after the edge key matched: that is the only reason to trust this header.
     * No other client-sent header is trusted for the address.
     */
    static String clientIp(GatewayRequest req) {
        String ip = req.header("cf-connecting-ip");
        if (ip == null) return null;
        ip = ip.strip();
        return IP.matcher(ip).matches() ? ip : null;
    }

    private static JsonNode json(GatewayRequest req) {
        if (req.body().length == 0) throw ApiException.badRequest("A JSON body is required");
        if (req.body().length > MAX_JSON_BYTES) {
            throw new ApiException(413, "body-too-large", "Request body is too large");
        }
        try {
            return Json.MAPPER.readTree(req.body());
        } catch (Exception e) {
            throw ApiException.badRequest("The body is not valid JSON");
        }
    }

    private long ms(long fromNanos) {
        return Math.max(0, (nanos.getAsLong() - fromNanos) / 1_000_000);
    }
}
