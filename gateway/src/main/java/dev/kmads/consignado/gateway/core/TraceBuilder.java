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

    public Views.RequestTrace build(long totalMs, long upstreamMs) {
        return new Views.RequestTrace(host.id(), cold(host, upstreamMs), totalMs, List.copyOf(events));
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
                totalMs, null);
    }

    /**
     * failure: a short detail when no upstream answer came ("timeout after 30 s", "function key not found in
     * Key Vault"); null for the default text. An upstream answer of 500 or more is an error stage too.
     */
    public static Views.RequestTrace forProxy(HostKind host, Instant now, boolean guest, long identityMs,
                                              long keyVaultMs, boolean keyVaultCached, boolean keyVaultUsed,
                                              long upstreamMs, int status, long totalMs, String failure) {
        TraceBuilder t = new TraceBuilder(host, now);
        long up = Math.max(upstreamMs, 0);
        long own = Math.max(0, totalMs - identityMs - keyVaultMs - up);
        boolean cold = cold(host, upstreamMs);
        boolean failed = upstreamMs < 0 || status == 0;
        t.done(Stages.REQUEST, "browser sent the request", null);
        t.done(Stages.EDGE, "Cloudflare edge: WAF, rate limit", null);
        t.done(Stages.GATEWAY, "gateway work without the calls below", own);
        t.done(Stages.IDENTITY, guest ? "guest: no token" : "Entra ID token verified", identityMs);
        if (host == HostKind.AKS) {
            t.done(Stages.QUOTA, "access window is open", null);
            t.done(Stages.KEYVAULT, "not needed for this host", 0L);
            t.stage(Stages.LOAD_BALANCER, failed ? LifecycleEvent.ERROR : LifecycleEvent.DONE,
                    failed ? (failure != null ? failure : "no answer from the internal load balancer")
                            : "internal load balancer to pod", null);
        } else {
            if (failure != null && failure.startsWith("function key")) {
                t.stage(Stages.KEYVAULT, LifecycleEvent.ERROR, failure, keyVaultMs);
            } else {
                t.done(Stages.KEYVAULT, !keyVaultUsed ? "not read" : keyVaultCached ? "cached" : "read from Key Vault",
                        keyVaultMs);
            }
            if (cold) {
                t.done(Stages.FUNCTION_COLD_START,
                        "cold block: instance allocation and JVM start, measured together", up);
                t.done(Stages.JVM_START, "inside the cold block of function-cold-start", null);
            }
            if (!guest) t.done(Stages.DATABASE, "inside the upstream time", null);
        }
        if (failed) {
            t.stage(Stages.RESPONSE, LifecycleEvent.ERROR,
                    failure != null && !failure.startsWith("function key") ? failure : "no answer from the API host", up);
        } else if (status >= 500) {
            t.stage(Stages.RESPONSE, LifecycleEvent.ERROR, "upstream answered " + status, up);
        } else if (cold) {
            t.done(Stages.RESPONSE, "HTTP " + status + "; the time is inside the cold block", null);
        } else {
            t.done(Stages.RESPONSE, "HTTP " + status + " from the API host", up);
        }
        return t.build(totalMs, up);
    }

    public static String encode(Views.RequestTrace trace) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.bytes(trace));
    }
}
