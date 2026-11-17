package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.ports.AzureMonitor;
import dev.kmads.consignado.gateway.ports.ClusterControl;
import dev.kmads.consignado.gateway.ports.ClusterObserver;
import dev.kmads.consignado.gateway.ports.Upstream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * GET /api/status?host=functions|aks (contract addendum, part 1). One answer per host, cached 15 seconds.
 * All sources are read in parallel, each with a time limit; a source that fails or is late makes only its
 * own service "unknown". Nothing here throws to the caller.
 */
public final class StatusService {
    public static final Duration CACHE = Duration.ofSeconds(15);
    /** Limit of one source in this answer. The adapters also use 3 s per HTTP call. */
    static final long SOURCE_LIMIT_MS = 3500;
    static final String NOT_CONFIGURED = "not configured";

    /** The Azure resource ids; null when the environment variable is missing. */
    public record Ids(String gatewayApp, String apiApp, String keyVault, String postgres, String aks) {}

    /** The signing keys of Entra ID: makes sure they are loaded and returns when they were fetched. */
    @FunctionalInterface
    public interface SigningKeys {
        Instant fetchedAt() throws Exception;
    }

    private final AzureMonitor monitor;
    private final ClusterControl cluster;
    private final ClusterObserver observer;
    private final Upstream upstream;
    private final SigningKeys keys;
    private final Ids ids;
    private final Clock clock;
    private final Executor executor;
    private final Map<HostKind, Views.PlatformStatus> cache = new java.util.EnumMap<>(HostKind.class);
    private final Map<HostKind, Instant> cachedAt = new java.util.EnumMap<>(HostKind.class);

    public StatusService(AzureMonitor monitor, ClusterControl cluster, ClusterObserver observer, Upstream upstream,
                         SigningKeys keys, Ids ids, Clock clock, Executor executor) {
        this.monitor = monitor;
        this.cluster = cluster;
        this.observer = observer;
        this.upstream = upstream;
        this.keys = keys;
        this.ids = ids;
        this.clock = clock;
        this.executor = executor;
    }

    public synchronized Views.PlatformStatus status(HostKind host) {
        Instant now = clock.instant();
        Instant at = cachedAt.get(host);
        if (at != null && Duration.between(at, now).compareTo(CACHE) < 0) return cache.get(host);
        Views.PlatformStatus fresh = build(host, now);
        cache.put(host, fresh);
        cachedAt.put(host, now);
        return fresh;
    }

    // ----------------------------------------------------------------- build

    private <T> CompletableFuture<T> async(Supplier<T> work, T fallback) {
        return CompletableFuture.supplyAsync(work, executor)
                .completeOnTimeout(fallback, SOURCE_LIMIT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> fallback);
    }

    private CompletableFuture<Optional<JsonNode>> metrics(String id, String service) {
        if (id == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(() -> monitor.metrics(id, StatusMetrics.names(service)), Optional.empty());
    }

    private CompletableFuture<Optional<JsonNode>> health(String id) {
        if (id == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(() -> monitor.health(id), Optional.empty());
    }

    private CompletableFuture<Optional<JsonNode>> resource(String id) {
        if (id == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(() -> monitor.resource(id), Optional.empty());
    }

    private record Probe(int status, long ms) {}

    private Views.PlatformStatus build(HostKind host, Instant now) {
        // Sources of both paths.
        var gwRes = resource(ids.gatewayApp());
        var gwHealth = health(ids.gatewayApp());
        var gwMetrics = metrics(ids.gatewayApp(), "gateway");
        var kvRes = resource(ids.keyVault());
        var kvHealth = health(ids.keyVault());
        var kvMetrics = metrics(ids.keyVault(), "keyvault");
        var pgRes = resource(ids.postgres());
        var pgMetrics = metrics(ids.postgres(), "database");
        CompletableFuture<Optional<Instant>> keysAt = async(() -> {
            try {
                return Optional.ofNullable(keys.fetchedAt());
            } catch (Exception e) {
                return Optional.empty();
            }
        }, Optional.empty());

        List<Views.ServiceStatus> out = new ArrayList<>();
        out.add(new Views.ServiceStatus("edge", "consignado-edge", "Cloudflare Worker", null, "up",
                "Cloudflare Worker consignado-edge; rate limit 40 requests / 10 s per IP", List.of(), List.of("gateway")));
        if (host == HostKind.FUNCTIONS) {
            var apiRes = resource(ids.apiApp());
            var apiHealth = health(ids.apiApp());
            var apiMetrics = metrics(ids.apiApp(), "functions");
            out.add(functionApp("gateway", ids.gatewayApp(), "Azure Function (Java)", gwRes, gwHealth, gwMetrics));
            out.add(identity(keysAt, now));
            out.add(keyVault(kvRes, kvHealth, kvMetrics));
            out.add(functionApp("functions", ids.apiApp(), "Azure Function (custom handler)", apiRes, apiHealth,
                    apiMetrics));
        } else {
            CompletableFuture<PowerState> power = async(cluster::powerState, PowerState.UNKNOWN);
            var aksRes = resource(ids.aks());
            var nodeMetrics = metrics(ids.aks(), "node");
            // Kubernetes and the readiness probe only when the cluster runs (no call to a stopped cluster).
            CompletableFuture<ClusterView> view = power.thenComposeAsync(p -> p.running()
                    ? async(observer::observe, ClusterView.DOWN)
                    : CompletableFuture.completedFuture(null), executor);
            CompletableFuture<Probe> probe = power.thenComposeAsync(p -> p.running()
                    ? async(this::probe, new Probe(0, 0)) : CompletableFuture.completedFuture(null), executor);
            out.add(functionApp("gateway", ids.gatewayApp(), "Azure Function (Java)", gwRes, gwHealth, gwMetrics));
            out.add(identity(keysAt, now));
            out.add(keyVault(kvRes, kvHealth, kvMetrics));
            out.add(loadBalancer(power.join(), probe.join()));
            out.add(clusterService(power.join(), aksRes.join()));
            out.add(node(power.join(), view.join(), nodeMetrics.join()));
            out.add(pods(power.join(), view.join(), now));
        }
        out.add(database(pgRes, pgMetrics));
        return new Views.PlatformStatus(Times.iso(now), host.id(), out);
    }

    private Probe probe() {
        long t = clock.millis();
        try {
            Upstream.Response r = upstream.call(new Upstream.Request(HostKind.AKS, "GET",
                    EngineService.READINESS_PATH, null, Map.of("Accept", "application/json"), null));
            return new Probe(r.status(), Math.max(0, clock.millis() - t));
        } catch (Upstream.UpstreamException e) {
            return new Probe(0, Math.max(0, clock.millis() - t));
        }
    }

    // -------------------------------------------------------------- services

    static String name(String resourceId, String fallback) {
        if (resourceId == null) return fallback;
        int slash = resourceId.lastIndexOf('/');
        return slash < 0 ? resourceId : resourceId.substring(slash + 1);
    }

    static String region(Optional<JsonNode> resource) {
        return resource.map(r -> r.path("location").asText(""))
                .filter(s -> !s.isBlank()).map(s -> s.replace(" ", "").toLowerCase(Locale.ROOT)).orElse(null);
    }

    private static Views.ServiceStatus unknown(String id, String name, String kind, String detail) {
        return new Views.ServiceStatus(id, name, kind, null, "unknown", detail, List.of(), List.of());
    }

    /** Resource health: Available, Degraded, Unavailable, Unknown. Null when not readable. */
    static String healthState(Optional<JsonNode> health) {
        return health.map(h -> h.path("properties").path("availabilityState").asText(null)).orElse(null);
    }

    /** The base state, then resource health can only make it worse. */
    private static String withHealth(String state, String health) {
        if (health == null || !"up".equals(state)) return state;
        return switch (health) {
            case "Unavailable" -> "down";
            case "Degraded" -> "degraded";
            default -> state;
        };
    }

    private static String healthText(String health) {
        return health == null ? "resource health: not readable" : "resource health: " + health;
    }

    private Views.ServiceStatus functionApp(String id, String resourceId, String kind,
                                            CompletableFuture<Optional<JsonNode>> res,
                                            CompletableFuture<Optional<JsonNode>> health,
                                            CompletableFuture<Optional<JsonNode>> metrics) {
        String name = name(resourceId, id.equals("gateway") ? "gateway" : "functions");
        if (resourceId == null) return unknown(id, name, kind, NOT_CONFIGURED);
        Optional<JsonNode> r = res.join();
        Optional<JsonNode> m = metrics.join();
        String h = healthState(health.join());
        List<String> source = sources(r, h, m);
        if (r.isEmpty() && m.isEmpty() && h == null) {
            return new Views.ServiceStatus(id, name, kind, null, "unknown", "Azure did not answer", List.of(), source);
        }
        String armState = r.map(x -> x.path("properties").path("state").asText(null)).orElse(null);
        String state = armState == null ? (id.equals("gateway") ? "up" : "unknown")
                : armState.equals("Running") ? "up" : armState.equals("Stopped") ? "stopped" : "unknown";
        // The gateway answers this request: it runs, whatever ARM is late to say.
        if (id.equals("gateway") && !"up".equals(state)) state = "up";
        state = withHealth(state, h);
        String detail = (armState == null ? "state not readable" : "State " + armState) + "; " + healthText(h);
        return new Views.ServiceStatus(id, name, kind, region(r), state, detail,
                m.map(x -> StatusMetrics.metrics(id, x)).orElse(List.of()), source);
    }

    private static List<String> sources(Optional<JsonNode> resource, String health, Optional<JsonNode> metrics) {
        List<String> s = new ArrayList<>();
        if (metrics.isPresent()) s.add("azure-monitor");
        if (health != null) s.add("resource-health");
        if (resource.isPresent()) s.add("arm");
        return s;
    }

    private Views.ServiceStatus identity(CompletableFuture<Optional<Instant>> keysAt, Instant now) {
        Optional<Instant> at = keysAt.join();
        if (at.isEmpty()) {
            return new Views.ServiceStatus("identity", "Microsoft Entra ID", "Microsoft Entra ID", null, "unknown",
                    "signing keys could not be fetched", List.of(), List.of("gateway"));
        }
        long minutes = Math.max(0, Duration.between(at.get(), now).toMinutes());
        return new Views.ServiceStatus("identity", "Microsoft Entra ID", "Microsoft Entra ID", null, "up",
                "signing keys fetched " + minutes + " min ago", List.of(), List.of("gateway"));
    }

    private Views.ServiceStatus keyVault(CompletableFuture<Optional<JsonNode>> res,
                                         CompletableFuture<Optional<JsonNode>> health,
                                         CompletableFuture<Optional<JsonNode>> metrics) {
        String name = name(ids.keyVault(), "keyvault");
        String kind = "Key Vault";
        if (ids.keyVault() == null) return unknown("keyvault", name, kind, NOT_CONFIGURED);
        Optional<JsonNode> r = res.join();
        Optional<JsonNode> m = metrics.join();
        String h = healthState(health.join());
        if (r.isEmpty() && m.isEmpty() && h == null) {
            return new Views.ServiceStatus("keyvault", name, kind, null, "unknown", "Azure did not answer", List.of(),
                    List.of());
        }
        Double availability = m.map(x -> StatusMetrics.reduce(x, "Availability", StatusMetrics.Agg.AVERAGE))
                .orElse(null);
        String state = availability == null || availability >= 99.9 ? "up" : availability > 0 ? "degraded" : "down";
        state = withHealth(state, h);
        String detail = (availability == null ? "availability: no data in the last 5 min"
                : "availability " + Math.round(availability * 10) / 10.0 + " % in the last 5 min") + "; "
                + healthText(h);
        return new Views.ServiceStatus("keyvault", name, kind, region(r), state, detail,
                m.map(x -> StatusMetrics.metrics("keyvault", x)).orElse(List.of()), sources(r, h, m));
    }

    private Views.ServiceStatus database(CompletableFuture<Optional<JsonNode>> res,
                                         CompletableFuture<Optional<JsonNode>> metrics) {
        String name = name(ids.postgres(), "database");
        String kind = "PostgreSQL flexible server";
        if (ids.postgres() == null) return unknown("database", name, kind, NOT_CONFIGURED);
        Optional<JsonNode> r = res.join();
        Optional<JsonNode> m = metrics.join();
        if (r.isEmpty() && m.isEmpty()) {
            return new Views.ServiceStatus("database", name, kind, null, "unknown", "Azure did not answer", List.of(),
                    List.of());
        }
        String armState = r.map(x -> x.path("properties").path("state").asText(null)).orElse(null);
        String state = armState == null ? "unknown" : switch (armState) {
            case "Ready" -> "up";
            case "Stopped", "Stopping" -> "stopped";
            case "Starting", "Updating" -> "starting";
            case "Disabled", "Dropping" -> "down";
            default -> "unknown";
        };
        Double alive = m.map(x -> StatusMetrics.reduce(x, StatusMetrics.DB_ALIVE, StatusMetrics.Agg.LATEST_MAX))
                .orElse(null);
        if (alive != null && alive < 1 && "up".equals(state)) state = "down";
        if (armState == null && alive != null) state = alive >= 1 ? "up" : "down";
        String detail = (armState == null ? "server state not readable" : "Server state " + armState)
                + (alive == null ? "" : alive >= 1 ? "; database answers (is_db_alive 1)" : "; is_db_alive 0");
        List<String> source = new ArrayList<>();
        if (m.isPresent()) source.add("azure-monitor");
        if (r.isPresent()) source.add("arm");
        return new Views.ServiceStatus("database", name, kind, region(r), state, detail,
                m.map(x -> StatusMetrics.metrics("database", x)).orElse(List.of()), source);
    }

    private static String powerText(PowerState p) {
        return "Power state " + p.code() + ", provisioning " + p.provisioning();
    }

    private Views.ServiceStatus clusterService(PowerState p, Optional<JsonNode> res) {
        String name = name(ids.aks(), "aks-consignado");
        if (!p.known()) {
            return new Views.ServiceStatus("cluster", name, "AKS cluster", region(res), "unknown",
                    "ARM did not answer", List.of(), List.of());
        }
        String state = p.running() ? "up" : p.starting() ? "starting" : "stopped";
        String detail = powerText(p) + (p.stopping() ? " (stop in progress)" : "");
        return new Views.ServiceStatus("cluster", name, "AKS cluster", region(res), state, detail, List.of(),
                List.of("arm"));
    }

    private Views.ServiceStatus stoppedPart(String id, String name, String kind, PowerState p) {
        if (!p.known()) return unknown(id, name, kind, "cluster state unknown (ARM did not answer)");
        String state = p.starting() ? "starting" : "stopped";
        return new Views.ServiceStatus(id, name, kind, null, state,
                "cluster is not running (" + powerText(p) + "); no Kubernetes call", List.of(), List.of("arm"));
    }

    private Views.ServiceStatus loadBalancer(PowerState p, Probe probe) {
        String name = "consignado-api (10.10.3.200)";
        String kind = "Internal load balancer";
        if (!p.running() || probe == null) return stoppedPart("load-balancer", name, kind, p);
        List<Views.Metric> latency = List.of(new Views.Metric("latency-avg", "Readiness call", (double) probe.ms(),
                "ms", "now"));
        if (probe.status() == 0) {
            return new Views.ServiceStatus("load-balancer", name, kind, null, "down",
                    "no answer from GET /" + EngineService.READINESS_PATH + " within the time limit", latency,
                    List.of("gateway"));
        }
        return new Views.ServiceStatus("load-balancer", name, kind, null, probe.status() == 200 ? "up" : "down",
                "GET /" + EngineService.READINESS_PATH + " answered " + probe.status() + " in " + probe.ms() + " ms",
                latency, List.of("gateway"));
    }

    private Views.ServiceStatus node(PowerState p, ClusterView view, Optional<JsonNode> metrics) {
        String kind = "Node";
        if (!p.running() || view == null) return stoppedPart("node", "node", kind, p);
        List<Views.Metric> m = metrics.map(x -> StatusMetrics.metrics("node", x)).orElse(List.of());
        List<String> source = new ArrayList<>(List.of("kubernetes"));
        if (metrics.isPresent()) source.add("azure-monitor");
        if (!view.apiServerUp()) {
            return new Views.ServiceStatus("node", "node", kind, null, "unknown",
                    "Kubernetes API did not answer", m, source);
        }
        long ready = view.nodes().stream().filter(ClusterView.Node::ready).count();
        int total = view.nodes().size();
        String name = total == 1 ? view.nodes().get(0).name() : total + " nodes";
        String state = total == 0 ? "down" : ready == total ? "up" : ready > 0 ? "degraded" : "down";
        return new Views.ServiceStatus("node", name, kind, null, state,
                ready + " of " + total + " nodes Ready (seen through the pods)", m, source);
    }

    private Views.ServiceStatus pods(PowerState p, ClusterView view, Instant now) {
        String kind = "Pods";
        String name = "consignado-api";
        if (!p.running() || view == null) return stoppedPart("pods", name, kind, p);
        if (!view.apiServerUp()) {
            return new Views.ServiceStatus("pods", name, kind, null, "unknown", "Kubernetes API did not answer",
                    List.of(), List.of("kubernetes"));
        }
        List<ClusterView.Pod> live = view.pods().stream()
                .filter(x -> !"Succeeded".equals(x.phase()) && !"Failed".equals(x.phase())).toList();
        long ready = live.stream().filter(ClusterView.Pod::ready).count();
        int restarts = live.stream().mapToInt(ClusterView.Pod::restarts).sum();
        String tag = live.stream().flatMap(x -> x.containers().stream()).map(ClusterView.Container::imageTag)
                .filter(t -> t != null).findFirst().orElse(null);
        Instant newest = live.stream().map(ClusterView.Pod::createdAt).filter(t -> t != null)
                .max(Instant::compareTo).orElse(null);
        String state = live.isEmpty() ? "down" : ready == live.size() ? "up" : ready > 0 ? "degraded" : "down";
        StringBuilder detail = new StringBuilder(ready + " of " + live.size() + " pods Ready, " + restarts
                + (restarts == 1 ? " restart" : " restarts"));
        if (tag != null) detail.append(", image tag ").append(tag);
        if (newest != null) {
            detail.append(", newest pod ").append(Math.max(0, Duration.between(newest, now).toMinutes()))
                    .append(" min old");
        }
        List<Views.Metric> m = List.of(
                new Views.Metric("replicas", "Ready pods", (double) ready, "count", "now"),
                new Views.Metric("restarts", "Restarts", (double) restarts, "count", "now"));
        return new Views.ServiceStatus("pods", name, kind, null, state, detail.toString(), m, List.of("kubernetes"));
    }
}
