package dev.kmads.consignado.gateway.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Builds the RequestTrace of one proxied call from measured times. */
public final class TraceBuilder {
    /**
     * Only the Functions host can be cold, and only a call of more than 8 s counts. A slow business call
     * (4 to 5 s for a contract activation, measured live) is not a cold start. The AKS host is never cold.
     */
    public static final long COLD_MS = 8000;

    public static boolean cold(HostKind host, long upstreamMs) {
        return host == HostKind.FUNCTIONS && upstreamMs > COLD_MS;
    }

    private final HostKind host;
    private final Instant now;
    private final List<LifecycleEvent> events = new ArrayList<>();

    public TraceBuilder(HostKind host, Instant now) {
        this.host = host;
        this.now = now;
    }

    public TraceBuilder stage(String stage, String status, String detail, Long ms) {
        events.add(new LifecycleEvent(Times.iso(now), stage, status, detail, ms));
        return this;
    }

    public TraceBuilder done(String stage, String detail, Long ms) {
        return stage(stage, LifecycleEvent.DONE, detail, ms);
    }

    public Views.RequestTrace build(long totalMs, long upstreamMs, ApiTiming api) {
        return new Views.RequestTrace(host.id(), cold(host, upstreamMs), totalMs, List.copyOf(events), api);
    }

    /**
     * The whole trace of a proxied call.
     * identityMs: token verification. keyVaultMs: secret read (0 and "cached" when no Key Vault call was made).
     * upstreamMs: the call to the API host, or -1 when no call was made. status: upstream status or 0.
     */
    public static Views.RequestTrace forProxy(HostKind host, Instant now, boolean guest, long identityMs,
                                              long keyVaultMs, boolean keyVaultCached, boolean keyVaultUsed,
                                              long upstreamMs, int status, long totalMs) {
        return forProxy(host, now, guest, identityMs, keyVaultMs, keyVaultCached, keyVaultUsed, upstreamMs, status,
                totalMs, null, null);
    }

    public static Views.RequestTrace forProxy(HostKind host, Instant now, boolean guest, long identityMs,
                                              long keyVaultMs, boolean keyVaultCached, boolean keyVaultUsed,
                                              long upstreamMs, int status, long totalMs, String failure) {
        return forProxy(host, now, guest, identityMs, keyVaultMs, keyVaultCached, keyVaultUsed, upstreamMs, status,
                totalMs, failure, null);
    }

    /**
     * failure: a short detail when no upstream answer came ("timeout after 30 s", "function key not found in
     * Key Vault"); null for the default text. An upstream answer of 500 or more is an error too.
     * api: the parsed X-Consignado-Timing of the answer, or null.
     *
     * <p>Invariant: the stage times add up to totalMs. Every measured part goes to exactly one stage: gateway
     * (the rest), identity, keyvault, and the upstream time. The upstream time goes to the host stage, app and
     * database when the API reported its own time; else to function-cold-start (cold call), the failed host stage
     * (error) or response (as before addendum 2).
     */
    public static Views.RequestTrace forProxy(HostKind host, Instant now, boolean guest, long identityMs,
                                              long keyVaultMs, boolean keyVaultCached, boolean keyVaultUsed,
                                              long upstreamMs, int status, long totalMs, String failure,
                                              ApiTiming api) {
        TraceBuilder t = new TraceBuilder(host, now);
        boolean called = upstreamMs >= 0;
        long up = Math.max(upstreamMs, 0);
        long id = guest ? 0 : identityMs;
        long own = Math.max(0, totalMs - id - keyVaultMs - up);
        boolean cold = cold(host, upstreamMs);
        boolean failed = upstreamMs < 0 || status == 0;
        boolean error = failed || status >= 500;
        // The API's own time is used only for a real answer, and only when it fits inside the measured call.
        ApiTiming timing = !error && api != null && api.appMs() <= up + 2 ? api : null;
        long appTotal = timing == null ? 0 : Math.min(Math.round(timing.appMs()), up);
        long dbMs = timing == null || !timing.touchedDatabase() ? 0 : Math.min(Math.round(timing.db().ms()), appTotal);
        long hostMs = up - appTotal;
        String errorDetail = failed ? (failure != null && !failure.startsWith("function key") ? failure
                : host == HostKind.AKS ? "no answer from the internal load balancer" : "no answer from the API host")
                : "upstream answered " + status;

        t.done(Stages.REQUEST, "browser sent the request", null);
        t.done(Stages.EDGE, "Cloudflare edge: WAF, rate limit", null);
        t.done(Stages.GATEWAY, "gateway work without the calls below", own);
        if (!guest) t.done(Stages.IDENTITY, "Entra ID token verified", identityMs);
        if (host == HostKind.AKS) {
            t.done(Stages.QUOTA, "access window is open", null);
            t.done(Stages.KEYVAULT, "not needed for this host", 0L);
            if (error) {
                t.stage(Stages.LOAD_BALANCER, LifecycleEvent.ERROR, errorDetail, up);
            } else if (timing != null) {
                t.done(Stages.LOAD_BALANCER, "internal load balancer to pod, and the way back", hostMs);
            } else {
                t.done(Stages.LOAD_BALANCER, "internal load balancer to pod", null);
            }
        } else {
            if (failure != null && failure.startsWith("function key")) {
                t.stage(Stages.KEYVAULT, LifecycleEvent.ERROR, failure, keyVaultMs);
            } else {
                t.done(Stages.KEYVAULT, !keyVaultUsed ? "not read" : keyVaultCached ? "cached" : "read from Key Vault",
                        keyVaultMs);
            }
            if (cold) {
                t.done(Stages.FUNCTION_COLD_START,
                        "cold block: instance allocation and JVM start, measured together", error ? up : hostMs);
                t.done(Stages.JVM_START, "inside the cold block of function-cold-start", null);
            }
            if (called && error) {
                t.stage(Stages.FUNCTIONS_HOST, LifecycleEvent.ERROR, errorDetail, cold ? null : up);
            } else if (timing != null) {
                if (cold) t.done(Stages.FUNCTIONS_HOST, "inside the cold block of function-cold-start", null);
                else t.done(Stages.FUNCTIONS_HOST, "Functions host: network, host routing and the way back", hostMs);
            }
        }
        if (timing != null) {
            t.done(Stages.APP, "Spring Boot handled the request in " + timing.instance(), appTotal - dbMs);
            if (timing.touchedDatabase()) t.done(Stages.DATABASE, timing.databaseDetail(), dbMs);
        }
        if (error) {
            // The upstream time is on the failed host stage (or on function-cold-start); none is left here.
            t.stage(Stages.RESPONSE, LifecycleEvent.ERROR, errorDetail, called ? null : 0L);
        } else if (timing != null) {
            t.done(Stages.RESPONSE, "HTTP " + status + " from the API host", null);
        } else if (cold) {
            t.done(Stages.RESPONSE, "HTTP " + status + "; the time is inside the cold block", null);
        } else {
            t.done(Stages.RESPONSE, "HTTP " + status + " from the API host", up);
        }
        return t.build(totalMs, upstreamMs, timing);
    }

    public static String encode(Views.RequestTrace trace) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.bytes(trace));
    }
}
