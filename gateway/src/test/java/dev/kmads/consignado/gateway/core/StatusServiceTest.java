package dev.kmads.consignado.gateway.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.http.GatewayRequest;
import dev.kmads.consignado.gateway.http.GatewayResponse;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** GET /api/status (contract addendum, part 1). Metric JSON: real az monitor answers of 2026-11-17, ids removed. */
class StatusServiceTest {
    private final World w = new World();

    static JsonNode fixture(String name) {
        try (InputStream in = StatusServiceTest.class.getResourceAsStream("/metrics/" + name + ".json")) {
            return Json.MAPPER.readTree(in);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static JsonNode json(String text) {
        try {
            return Json.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void azureAnswers() {
        World.Monitor m = w.monitor;
        m.metrics.put(World.IDS.gatewayApp(), fixture("function-app"));
        m.metrics.put(World.IDS.apiApp(), fixture("function-app"));
        m.metrics.put(World.IDS.keyVault(), fixture("key-vault"));
        m.metrics.put(World.IDS.postgres(), fixture("postgres"));
        m.metrics.put(World.IDS.aks(), fixture("aks"));
        m.resources.put(World.IDS.gatewayApp(), json("{\"location\":\"BelgiumCentral\",\"properties\":{\"state\":\"Running\"}}"));
        m.resources.put(World.IDS.apiApp(), json("{\"location\":\"BelgiumCentral\",\"properties\":{\"state\":\"Running\"}}"));
        m.resources.put(World.IDS.keyVault(), json("{\"location\":\"belgiumcentral\",\"properties\":{\"provisioningState\":\"Succeeded\"}}"));
        m.resources.put(World.IDS.postgres(), json("{\"location\":\"NorthCentralUS\",\"properties\":{\"state\":\"Ready\"}}"));
        m.resources.put(World.IDS.aks(), json("{\"location\":\"belgiumcentral\"}"));
        m.health.put(World.IDS.apiApp(), json("{\"properties\":{\"availabilityState\":\"Available\"}}"));
        m.metrics.put(World.IDS.appInsights(), fixture("app-insights-requests"));
    }

    // ------------------------------------------------- Application Insights

    @Test
    void requestMetricsOfRealAnswerPerRole() {
        JsonNode appi = fixture("app-insights-requests");
        List<Views.Metric> gw = StatusMetrics.requestMetrics(appi, "func-consignado-gw-km01");
        assertEquals(List.of("requests", "errors", "latency-avg"), gw.stream().map(Views.Metric::key).toList());
        assertEquals(192.0, gw.get(0).value());
        assertEquals(0.0, gw.get(1).value(), "requests/failed has no series for a role without failures: 0");
        assertEquals(81.9, gw.get(2).value(), "weighted by the count of each minute");
        assertEquals("ms", gw.get(2).unit());
        List<Views.Metric> api = StatusMetrics.requestMetrics(appi, "func-consignado-api-km01");
        assertEquals(0.0, api.get(0).value());
        assertNull(api.get(2).value(), "no request: no duration");
        List<Views.Metric> other = StatusMetrics.requestMetrics(appi, "unknown-app");
        assertNull(other.get(0).value());
    }

    @Test
    void failedRequestsAreCountedPerRole() {
        JsonNode answer = json("{\"value\":[{\"name\":{\"value\":\"requests/failed\"},\"timeseries\":["
                + "{\"metadatavalues\":[{\"name\":{\"value\":\"cloud/rolename\"},\"value\":\"func-consignado-api-km01\"}],"
                + "\"data\":[{\"count\":2},{\"count\":1},{}]}]}]}");
        assertEquals(3.0, StatusMetrics.requestMetrics(answer, "func-consignado-api-km01").get(1).value());
        assertNull(StatusMetrics.requestMetrics(json("{\"value\":[]}"), "func-consignado-api-km01").get(1).value(),
                "metric not in the answer: no data");
    }

    private GatewayResponse get(String query, String token) {
        Map<String, String> headers = new HashMap<>();
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return w.handlers.status(GatewayRequest.of("GET", null, query, headers, null));
    }

    private static JsonNode service(JsonNode status, String id) {
        for (JsonNode s : status.get("services")) if (s.get("id").asText().equals(id)) return s;
        throw new AssertionError("no service " + id);
    }

    private static Double metric(JsonNode service, String key) {
        for (JsonNode m : service.get("metrics")) {
            if (m.get("key").asText().equals(key)) return m.get("value").isNull() ? null : m.get("value").asDouble();
        }
        throw new AssertionError("no metric " + key + " in " + service);
    }

    private static List<String> ids(JsonNode status) {
        List<String> out = new ArrayList<>();
        status.get("services").forEach(s -> out.add(s.get("id").asText()));
        return out;
    }

    // ------------------------------------------------------------ parsing

    @Test
    void metricParsingOfRealAnswers() {
        JsonNode fn = fixture("function-app");
        assertEquals(0.0, StatusMetrics.reduce(fn, "OnDemandFunctionExecutionCount", StatusMetrics.Agg.TOTAL));
        assertEquals(1.0, StatusMetrics.reduce(fn, "InstanceCount", StatusMetrics.Agg.AVERAGE));
        List<Views.Metric> fm = StatusMetrics.metrics("functions", fn);
        assertEquals(List.of("executions", "instances", "cpu", "memory"), fm.stream().map(Views.Metric::key).toList());
        assertEquals(123.0, fm.get(3).value(), "bytes -> MB");
        assertEquals("MB", fm.get(3).unit());

        List<Views.Metric> kv = StatusMetrics.metrics("keyvault", fixture("key-vault"));
        assertEquals(10.0, kv.get(0).value());
        assertEquals(58.9, kv.get(1).value());
        assertEquals("ms", kv.get(1).unit());
        assertEquals(100.0, kv.get(2).value());

        JsonNode pg = fixture("postgres");
        List<Views.Metric> db = StatusMetrics.metrics("database", pg);
        assertEquals(List.of(7.6, 59.1, 28.4), db.stream().map(Views.Metric::value).toList());
        assertEquals(1.0, StatusMetrics.reduce(pg, "is_db_alive", StatusMetrics.Agg.LATEST_MAX));

        List<Views.Metric> node = StatusMetrics.metrics("node", fixture("aks"));
        assertEquals(List.of(9.6, 31.8), node.stream().map(Views.Metric::value).toList());
        assertNull(StatusMetrics.reduce(fn, "Http5xx", StatusMetrics.Agg.TOTAL), "a name not in the answer is null");
        assertTrue(StatusMetrics.names("database").contains("is_db_alive"));
    }

    @Test
    void aMetricWithNoDataPointIsNull() {
        JsonNode empty = json("{\"value\":[{\"name\":{\"value\":\"ServiceApiLatency\"},\"timeseries\":[{\"data\":"
                + "[{\"timeStamp\":\"2026-11-17T20:47:00Z\"}]}]}]}");
        assertNull(StatusMetrics.reduce(empty, "ServiceApiLatency", StatusMetrics.Agg.AVERAGE));
        Views.Metric latency = StatusMetrics.metrics("keyvault", empty).get(1);
        assertNull(latency.value());
        assertTrue(new String(Json.bytes(latency)).contains("\"value\":null"), "null is sent, not left out");
    }

    // ------------------------------------------------------- functions path

    @Test
    void functionsPathInRequestOrderWithRealValues() {
        azureAnswers();
        GatewayResponse r = get("host=functions", null);
        assertEquals(200, r.status());
        JsonNode s = World.json(r);
        assertEquals("functions", s.get("host").asText());
        assertEquals("2026-11-17T12:00:00Z", s.get("asOf").asText());
        assertEquals(List.of("edge", "gateway", "identity", "keyvault", "functions", "database"), ids(s));
        JsonNode fn = service(s, "functions");
        assertEquals("func-consignado-api-km01", fn.get("name").asText());
        assertEquals("Azure Function (custom handler)", fn.get("kind").asText());
        assertEquals("belgiumcentral", fn.get("region").asText());
        assertEquals("up", fn.get("state").asText());
        assertEquals("State Running. Resource health: Available", fn.get("detail").asText());
        assertEquals(1.0, metric(fn, "instances"));
        assertEquals(0.0, metric(fn, "requests"));
        assertEquals(192.0, metric(service(s, "gateway"), "requests"));
        assertEquals(0.0, metric(service(s, "gateway"), "errors"));
        assertEquals(81.9, metric(service(s, "gateway"), "latency-avg"));
        assertEquals(List.of("azure-monitor", "resource-health", "arm"),
                Json.MAPPER.convertValue(fn.get("source"), List.class));
        JsonNode db = service(s, "database");
        assertEquals("psql-consignado-km01", db.get("name").asText());
        assertEquals("northcentralus", db.get("region").asText());
        assertEquals("up", db.get("state").asText());
        assertEquals(28.4, metric(db, "connections"));
        JsonNode kv = service(s, "keyvault");
        assertEquals("up", kv.get("state").asText());
        assertEquals(58.9, metric(kv, "latency-avg"));
        assertEquals("signing keys fetched 3 min ago", service(s, "identity").get("detail").asText());
        assertEquals("up", service(s, "edge").get("state").asText());
        assertFalse(service(s, "edge").has("region"));
        assertEquals("Resource health: not readable", service(s, "gateway").get("detail").asText()
                .replace("State Running. ", ""), "health of the gateway was not given");
    }

    @Test
    void healthAndStateMakeAServiceWorse() {
        azureAnswers();
        w.monitor.health.put(World.IDS.apiApp(), json("{\"properties\":{\"availabilityState\":\"Degraded\"}}"));
        w.monitor.resources.put(World.IDS.postgres(), json("{\"location\":\"NorthCentralUS\",\"properties\":{\"state\":\"Stopped\"}}"));
        JsonNode s = World.json(get("host=functions", null));
        assertEquals("degraded", service(s, "functions").get("state").asText());
        assertEquals("stopped", service(s, "database").get("state").asText());
    }

    @Test
    void aMissingIdIsUnknownNotConfiguredAndTheRouteStillAnswers() {
        StatusService none = new StatusService(w.monitor, w.cluster, w.cluster, w.upstream, () -> null,
                new StatusService.Ids(null, null, null, null, null), w.clock, Executors.newCachedThreadPool());
        Views.PlatformStatus st = none.status(HostKind.FUNCTIONS);
        for (Views.ServiceStatus x : st.services()) {
            if (List.of("gateway", "keyvault", "functions", "database").contains(x.id())) {
                assertEquals("unknown", x.state(), x.id());
                assertEquals("not configured", x.detail(), x.id());
            }
        }
        assertEquals(0, w.monitor.calls.get(), "no Azure call without an id");
    }

    @Test
    void aFailedSourceMakesOnlyThatServiceUnknown() {
        azureAnswers();
        w.monitor.metrics.remove(World.IDS.keyVault());
        w.monitor.resources.remove(World.IDS.keyVault());
        w.keysFetchedAt = null;
        JsonNode s = World.json(get("host=functions", null));
        assertEquals("unknown", service(s, "keyvault").get("state").asText());
        assertEquals("Azure did not answer", service(s, "keyvault").get("detail").asText());
        assertEquals("unknown", service(s, "identity").get("state").asText());
        assertEquals("up", service(s, "functions").get("state").asText());
        assertEquals("up", service(s, "database").get("state").asText());
    }

    @Test
    void aSlowSourceIsCutAtTheLimitAndTheSourcesRunInParallel() {
        azureAnswers();
        w.monitor.slow.add(World.IDS.postgres());
        long t = System.nanoTime();
        JsonNode s = World.json(get("host=functions", null));
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 4500, "answer in " + ms + " ms");
        assertEquals("unknown", service(s, "database").get("state").asText());
        assertEquals("up", service(s, "functions").get("state").asText());
    }

    @Test
    void cachedFor15SecondsPerHost() {
        azureAnswers();
        get("host=functions", null);
        int calls = w.monitor.calls.get();
        w.clock.plus(Duration.ofSeconds(14));
        get("host=functions", null);
        assertEquals(calls, w.monitor.calls.get());
        w.clock.plus(Duration.ofSeconds(1));
        JsonNode s = World.json(get("host=functions", null));
        assertTrue(w.monitor.calls.get() > calls);
        assertEquals("2026-11-17T12:00:15Z", s.get("asOf").asText());
    }

    @Test
    void routeRules() {
        assertEquals(400, get(null, null).status());
        assertEquals(400, get("host=other", null).status());
        assertEquals(401, get("host=aks", null).status());
        assertEquals(200, get("host=aks", "good-u1").status());
    }

    // ------------------------------------------------------------- aks path

    @Test
    void aksPathWithAStoppedClusterMakesNoKubernetesCall() {
        azureAnswers();
        w.cluster.stopped();
        JsonNode s = World.json(get("host=aks", "good-u1"));
        assertEquals(List.of("edge", "gateway", "identity", "keyvault", "load-balancer", "cluster", "node", "pods",
                "database"), ids(s));
        assertEquals("stopped", service(s, "cluster").get("state").asText());
        assertEquals("Power state Stopped, provisioning Succeeded", service(s, "cluster").get("detail").asText());
        for (String id : List.of("load-balancer", "node", "pods")) {
            assertEquals("stopped", service(s, id).get("state").asText(), id);
        }
        assertTrue(w.upstream.calls.isEmpty(), "no readiness call");
        assertEquals(0, w.cluster.observations, "no Kubernetes call");
    }

    @Test
    void aksPathWithARunningCluster() {
        azureAnswers();
        w.cluster.running();
        w.cluster.view = World.readyView();
        w.upstream.readiness = 200;
        JsonNode s = World.json(get("host=aks", "good-u1"));
        assertEquals("up", service(s, "cluster").get("state").asText());
        assertEquals("belgiumcentral", service(s, "cluster").get("region").asText());
        JsonNode lb = service(s, "load-balancer");
        assertEquals("up", lb.get("state").asText());
        assertNotNull(metric(lb, "latency-avg"));
        JsonNode node = service(s, "node");
        assertEquals("up", node.get("state").asText());
        assertEquals(9.6, metric(node, "cpu"));
        JsonNode pods = service(s, "pods");
        assertEquals("up", pods.get("state").asText());
        assertTrue(pods.get("detail").asText().startsWith("1 of 1 pods Ready, 0 restarts"), pods.toString());
        assertEquals(1.0, metric(pods, "replicas"));
        assertFalse(pods.get("detail").asText().contains("Deployment"), "no workload read: from the pod list");

        w.clock.plus(Duration.ofSeconds(15));
        w.cluster.workload = java.util.Optional.of(new ClusterView.Workload(
                List.of(new ClusterView.Deployment("consignado-api", 2, 1)),
                List.of(new ClusterView.Hpa("consignado-api", 1, 2, 1, 3))));
        pods = service(World.json(get("host=aks", "good-u1")), "pods");
        assertEquals("degraded", pods.get("state").asText());
        assertEquals(1.0, metric(pods, "replicas"));
        assertTrue(pods.get("detail").asText().endsWith(
                ". Deployment consignado-api: 1 of 2 replicas ready. HPA consignado-api: current 1, desired 2 (1 to 3)"),
                pods.get("detail").asText());

        w.clock.plus(Duration.ofSeconds(15));
        w.upstream.readiness = 503;
        assertEquals("down", service(World.json(get("host=aks", "good-u1")), "load-balancer").get("state").asText());
    }

    // ------------------------------------------------------------- build (contract addendum 2)

    private static ClusterView.Pod pod(String name, String image, boolean ready, String created) {
        ClusterView.Container c = new ClusterView.Container("api", "running", null, true, 0, image);
        return new ClusterView.Pod(name, "aks-nodepool1-123-vmss000000", "Running", true, true, true, ready, ready,
                List.of(c), created == null ? null : java.time.Instant.parse(created), ClusterView.PodTimes.NONE);
    }

    @Test
    void podsCarryTheBuildOfTheRunningImageAndFunctionsDoNot() {
        azureAnswers();
        w.cluster.running();
        String sha = "0dae5d2c4b1f9e8a7d6c5b4a39281706f5e4d3c2";
        w.cluster.view = new ClusterView(true, List.of(new ClusterView.Node("aks-nodepool1-123-vmss000000", true)),
                List.of(pod("consignado-api-old", "crconsignadokm01.azurecr.io/consignado-api:1111111", true,
                                "2026-11-17T10:00:00Z"),
                        pod("consignado-api-new", "crconsignadokm01.azurecr.io/consignado-api:" + sha, true,
                                "2026-11-17T11:00:00Z")), 2);
        w.upstream.readiness = 200;
        JsonNode pods = service(World.json(get("host=aks", "good-u1")), "pods");
        assertEquals(sha, pods.get("build").get("commit").asText(), "the newest Ready pod");
        assertEquals("kmadsdev/consignado-api", pods.get("build").get("repository").asText());
        assertFalse(service(World.json(get("host=aks", "good-u1")), "cluster").has("build"));

        JsonNode fn = service(World.json(get("host=functions", null)), "functions");
        assertFalse(fn.has("build"), "the Functions host data carries no commit");
    }

    @Test
    void buildIsLeftOutWhenTheTagIsNotACommit() {
        assertNull(StatusService.build(List.of(pod("p", "cr.io/consignado-api:latest", true, null)), "consignado-api"));
        assertNull(StatusService.build(List.of(pod("p", "cr.io/consignado-api", true, null)), "consignado-api"));
        assertNull(StatusService.build(List.of(pod("p", "cr.io/other:abcdef1", true, null)), "consignado-api"),
                "only the API image counts");
        assertNull(StatusService.build(List.of(pod("p", "cr.io/consignado-api:ABCDEF1", true, null)), "consignado-api"));
        assertNull(StatusService.build(List.of(), "consignado-api"));
        assertEquals("abcdef1", StatusService.build(List.of(pod("p", "cr.io/consignado-api:abcdef1", false, null)),
                "consignado-api").commit(), "any live pod when none is Ready");
        assertEquals("consignado-api", new ClusterView.Container("a", "running", null, true, 0,
                "cr.io:5000/team/consignado-api:abc@sha256:00").imageName());
    }
}
