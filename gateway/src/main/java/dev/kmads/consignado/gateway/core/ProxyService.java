package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.Secrets;
import dev.kmads.consignado.gateway.ports.SessionStore;
import dev.kmads.consignado.gateway.ports.Upstream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** The rules of /api/fn/** and /api/aks/**: who may call what, what is forwarded, and the trace. */
public final class ProxyService {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final String HOST_KEY = "apifn-host-key";
    public static final String GUEST_PATH = "api/v1/simulations/preview";

    private static final Set<String> METHODS = Set.of("GET", "POST", "PATCH");
    /** Only these request headers go upstream. Authorization, cookies and hop-by-hop headers never do. */
    static final List<String> FORWARDED = List.of("accept", "accept-language", "content-type", "idempotency-key",
            "if-none-match", "x-request-id");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9/_\\-.~]{1,512}$");
    private static final Pattern QUERY = Pattern.compile("^[A-Za-z0-9_\\-.~%&=+,:@/]{0,1024}$");

    public record Result(int status, String contentType, byte[] body, HostKind host, Views.RequestTrace trace) {}

    private final Upstream upstream;
    private final Secrets secrets;
    private final SessionStore sessions;
    private final Clock clock;
    private final LongSupplier nanos;

    public ProxyService(Upstream upstream, Secrets secrets, SessionStore sessions, Clock clock, LongSupplier nanos) {
        this.upstream = upstream;
        this.secrets = secrets;
        this.sessions = sessions;
        this.clock = clock;
        this.nanos = nanos;
    }

    /**
     * user is null for a guest. headers have lower-case names. startNanos is the time the gateway
     * accepted the request, from the same nano clock.
     */
    public Result forward(HostKind host, String method, String rawPath, String rawQuery, Map<String, String> headers,
                          byte[] body, User user, long identityMs, long startNanos) {
        return forward(host, method, rawPath, rawQuery, headers, body, user, false, identityMs, startNanos);
    }

    /** admin is true for the owner: only the owner may write agreements (their names are public in the snapshot). */
    public Result forward(HostKind host, String method, String rawPath, String rawQuery, Map<String, String> headers,
                          byte[] body, User user, boolean admin, long identityMs, long startNanos) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) throw new ApiException(405, "method-not-allowed", "Method not allowed");
        String path = cleanPath(rawPath);
        String query = rawQuery == null || rawQuery.isEmpty() ? null : rawQuery;
        if (query != null && !QUERY.matcher(query).matches()) throw ApiException.badRequest("Query is not valid");
        if (body != null && body.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "body-too-large", "Request body is larger than 64 KB");
        }

        boolean guest = user == null;
        if (guest) {
            // A guest may call one stateless route of the Functions host, and nothing else.
            if (host != HostKind.FUNCTIONS || !m.equals("POST") || !path.equals(GUEST_PATH)) {
                throw ApiException.unauthorized();
            }
        } else if (User.BLOCKED.equals(user.status)) {
            throw new ApiException(403, "blocked", "This account is blocked");
        } else if (!admin && !m.equals("GET") && isAgreementPath(path)) {
            // Agreement names are free text and the public snapshot shows them: only the owner writes them.
            throw ApiException.forbidden();
        }
        Instant now = clock.instant();
        if (host == HostKind.AKS) requireOpenWindow(user, now);

        Map<String, String> out = new LinkedHashMap<>();
        for (String name : FORWARDED) {
            String value = headers == null ? null : headers.get(name);
            if (value != null && value.length() <= 1024 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
                out.put(name, value);
            }
        }
        out.put("X-Consignado-User", guest ? "guest" : user.id);

        long kvMs = 0;
        boolean kvCached = false;
        boolean kvUsed = false;
        if (host == HostKind.FUNCTIONS) {
            long t = nanos.getAsLong();
            Secrets.Read key;
            try {
                key = secrets.read(HOST_KEY);
            } catch (RuntimeException e) {
                key = new Secrets.Read(null, false);
            }
            kvUsed = true;
            kvCached = key.cached();
            kvMs = kvCached ? 0 : ms(t);
            if (!key.found()) {
                return failure(host, now, guest, identityMs, kvMs, kvCached, kvUsed, -1, 502, startNanos,
                        "function key not found in Key Vault");
            }
            out.put("x-functions-key", key.value());
        }

        long t = nanos.getAsLong();
        Upstream.Response response;
        try {
            response = upstream.call(new Upstream.Request(host, m, path, query, out,
                    body == null || body.length == 0 ? null : body));
        } catch (Upstream.UpstreamException e) {
            return failure(host, now, guest, identityMs, kvMs, kvCached, kvUsed, ms(t), e.timeout() ? 504 : 502,
                    startNanos, e.timeout() ? "timeout after " + host.upstreamTimeout().toSeconds() + " s"
                            : "no answer from the API host (connection failed)");
        }
        long upstreamMs = ms(t);
        Views.RequestTrace trace = TraceBuilder.forProxy(host, now, guest, identityMs, kvMs, kvCached, kvUsed,
                upstreamMs, response.status(), ms(startNanos));
        String contentType = response.contentType() == null ? "application/json" : response.contentType();
        return new Result(response.status(), contentType, response.body() == null ? new byte[0] : response.body(),
                host, trace);
    }

    private Result failure(HostKind host, Instant now, boolean guest, long identityMs, long kvMs, boolean kvCached,
                           boolean kvUsed, long upstreamMs, int status, long startNanos, String detail) {
        Views.RequestTrace trace = TraceBuilder.forProxy(host, now, guest, identityMs, kvMs, kvCached, kvUsed,
                upstreamMs < 0 ? -1 : upstreamMs, 0, ms(startNanos), detail);
        Views.ErrorBody error = status == 504
                ? new Views.ErrorBody("upstream-timeout", "The API host did not answer in time")
                : new Views.ErrorBody("upstream-unavailable", "The API host is not available now");
        return new Result(status, "application/json", Json.bytes(error), host, trace);
    }

    private void requireOpenWindow(User user, Instant now) {
        Session session = user.activeSessionId == null ? null : sessions.find(user.activeSessionId).orElse(null);
        boolean open = session != null && session.userId.equals(user.id) && Session.READY.equals(session.state)
                && session.expiresAt != null && now.isBefore(session.expiresAt);
        if (!open) throw new ApiException(403, "no-session", "An open access window is required for this host");
    }

    static String cleanPath(String rawPath) {
        if (rawPath == null) throw ApiException.notFound();
        String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (!path.startsWith("api/v1/") || !PATH.matcher(path).matches() || path.contains("..")
                || path.contains("//")) {
            throw ApiException.notFound();
        }
        return path;
    }

    private long ms(long fromNanos) {
        return Math.max(0, (nanos.getAsLong() - fromNanos) / 1_000_000);
    }

    static boolean isAgreementPath(String path) {
        return path.equals(AGREEMENTS) || path.startsWith(AGREEMENTS + "/");
    }

    private static final String AGREEMENTS = "api/v1/agreements";
}
