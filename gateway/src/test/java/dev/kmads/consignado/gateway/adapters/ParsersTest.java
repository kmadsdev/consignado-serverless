package dev.kmads.consignado.gateway.adapters;

import static org.junit.jupiter.api.Assertions.*;

import dev.kmads.consignado.gateway.core.ClusterView;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.core.PowerState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The logic inside the adapters that needs no network: JSON of ARM, Kubernetes and Cost Management. */
class ParsersTest {

    @Test
    void armPowerState() throws Exception {
        PowerState running = ArmClusterControl.parse("{\"properties\":{\"provisioningState\":\"Succeeded\",\"powerState\":{\"code\":\"Running\"}}}");
        assertTrue(running.running());
        PowerState starting = ArmClusterControl.parse("{\"properties\":{\"provisioningState\":\"Starting\",\"powerState\":{\"code\":\"Stopped\"}}}");
        assertTrue(starting.starting() && starting.transitioning() && !starting.stopped() && !starting.running());
        PowerState stopped = ArmClusterControl.parse("{\"properties\":{\"provisioningState\":\"Succeeded\",\"powerState\":{\"code\":\"Stopped\"}}}");
        assertTrue(stopped.stopped());
        assertFalse(ArmClusterControl.parse("{}").known());
    }

    @Test
    void kubernetesNodesPodsAndEndpoints() throws Exception {
        List<ClusterView.Node> nodes = KubeParser.nodes(Json.MAPPER.readTree(
                "{\"items\":[{\"metadata\":{\"name\":\"node-a\"},\"status\":{\"conditions\":[{\"type\":\"MemoryPressure\",\"status\":\"False\"},{\"type\":\"Ready\",\"status\":\"True\"}]}},"
                        + "{\"metadata\":{\"name\":\"node-b\"},\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"Unknown\"}]}}]}"));
        assertEquals(List.of(new ClusterView.Node("node-a", true), new ClusterView.Node("node-b", false)), nodes);

        List<ClusterView.Pod> pods = KubeParser.pods(Json.MAPPER.readTree("{\"items\":["
                + "{\"metadata\":{\"name\":\"api-1\"},\"spec\":{\"nodeName\":\"node-a\"},\"status\":{\"phase\":\"Pending\",\"conditions\":[{\"type\":\"PodScheduled\",\"status\":\"True\"},{\"type\":\"Initialized\",\"status\":\"True\"},{\"type\":\"Ready\",\"status\":\"False\"}],"
                + "\"containerStatuses\":[{\"name\":\"api\",\"imageID\":\"\",\"state\":{\"waiting\":{\"reason\":\"ContainerCreating\"}}}]}},"
                + "{\"metadata\":{\"name\":\"api-2\"},\"spec\":{\"nodeName\":\"node-a\"},\"status\":{\"phase\":\"Running\",\"conditions\":[{\"type\":\"PodScheduled\",\"status\":\"True\"},{\"type\":\"PodReadyToStartContainers\",\"status\":\"True\"},{\"type\":\"ContainersReady\",\"status\":\"True\"},{\"type\":\"Ready\",\"status\":\"True\"}],"
                + "\"containerStatuses\":[{\"name\":\"api\",\"imageID\":\"cr.example/api@sha256:abc\",\"state\":{\"running\":{\"startedAt\":\"2026-11-17T12:00:00Z\"}}}]}},"
                + "{\"metadata\":{\"name\":\"api-old\",\"deletionTimestamp\":\"2026-11-17T12:00:00Z\"},\"status\":{}}]}"));
        assertEquals(2, pods.size());
        ClusterView.Pod creating = pods.get(0);
        assertTrue(creating.scheduled());
        assertFalse(creating.imagePulled() || creating.volumesMounted() || creating.containersStarted() || creating.ready());
        assertEquals("ContainerCreating", creating.waitingReason());
        ClusterView.Pod ready = pods.get(1);
        assertTrue(ready.imagePulled() && ready.volumesMounted() && ready.containersStarted() && ready.ready());

        ClusterView view = new ClusterView(true, nodes, pods, KubeParser.readyEndpoints(Json.MAPPER.readTree(
                "{\"items\":[{\"subsets\":[{\"addresses\":[{\"ip\":\"10.244.0.5\"}],\"notReadyAddresses\":[{\"ip\":\"10.244.0.6\"}]}]},{\"metadata\":{\"name\":\"empty\"}}]}")));
        assertEquals(1, view.readyEndpoints());
        assertEquals("api-2", view.bestPod().name());
        assertEquals("node-a", view.readyNode().name());
        assertNull(ClusterView.DOWN.bestPod());
    }

    @Test
    void armRequestsAreBuiltWithoutRestrictedHeaders() {
        String id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-consignado"
                + "/providers/Microsoft.ContainerService/managedClusters/aks-consignado";
        for (String action : new String[] {"start", "stop"}) {
            java.net.http.HttpRequest r = ArmClusterControl.actionRequest(id, action, "token-value");
            assertEquals("POST", r.method());
            assertEquals("https://management.azure.com" + id + "/" + action + "?api-version=2024-09-01", r.uri().toString());
            assertEquals("Bearer token-value", r.headers().firstValue("Authorization").orElseThrow());
            assertEquals(java.util.Set.of("Accept", "Authorization"), r.headers().map().keySet(),
                    "Content-Length is set by the HTTP client, never by this code");
            assertEquals(0L, r.bodyPublisher().orElseThrow().contentLength(), "an empty body, but a body publisher");
            assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, r.version().orElseThrow(),
                    "HTTP/1.1: the client sends Content-Length: 0 (ARM answers 411 without it)");
        }
        assertThrows(IllegalArgumentException.class, () -> ArmClusterControl.actionRequest(id, "delete", "t"));
        java.net.http.HttpRequest get = ArmClusterControl.getRequest(id, "token-value");
        assertEquals("GET", get.method());
        assertEquals("https://management.azure.com" + id + "?api-version=2024-09-01", get.uri().toString());
    }

    @Test
    void nodeComesFromThePodsWhenTheNodeListIsForbidden() {
        ClusterView.Container running = new ClusterView.Container("api", "running", null, true);
        ClusterView.Container waiting = new ClusterView.Container("api", "waiting", "ContainerCreating", false);
        List<ClusterView.Pod> pods = List.of(
                new ClusterView.Pod("api-1", "node-a", "Running", true, true, true, true, true, List.of(running)),
                new ClusterView.Pod("api-2", "node-b", "Pending", true, true, false, false, false, List.of(waiting)),
                new ClusterView.Pod("api-3", "", "Pending", false, false, false, false, false, List.of()));
        assertEquals(List.of(new ClusterView.Node("node-a", true), new ClusterView.Node("node-b", false)),
                KubeParser.nodesFromPods(pods));
        assertTrue(KubeParser.nodesFromPods(List.of()).isEmpty());
    }

    @Test
    void costQueryResult() throws Exception {
        assertEquals(12.35, CostManagementReader.parse("{\"properties\":{\"columns\":[{\"name\":\"CostUSD\"},{\"name\":\"Currency\"}],\"rows\":[[12.3456,\"USD\"]]}}"));
        assertEquals(0.0, CostManagementReader.parse("{\"properties\":{\"rows\":[]}}"));
        assertThrows(Exception.class, () -> CostManagementReader.parse("{\"properties\":{\"rows\":[[\"USD\"]]}}"));
        // The live answer of 2026-11-18 (aggregation "Cost"): the amount and the billing currency.
        String live = "{\"properties\":{\"columns\":[{\"name\":\"Cost\",\"type\":\"Number\"},"
                + "{\"name\":\"Currency\",\"type\":\"String\"}],\"rows\":[[5.49229332639736,\"BRL\"]]}}";
        assertEquals(5.49, CostManagementReader.parse(live));
        assertEquals("BRL", CostManagementReader.currency(live));
        assertEquals("USD", CostManagementReader.currency("{\"properties\":{\"columns\":[{\"name\":\"Currency\"},{\"name\":\"Cost\"}],\"rows\":[[\"usd\",1.5]]}}"));
        assertNull(CostManagementReader.currency("{\"properties\":{\"columns\":[{\"name\":\"Cost\"}],\"rows\":[[1.5]]}}"));
        assertNull(CostManagementReader.currency("{\"properties\":{\"columns\":[{\"name\":\"Cost\"},{\"name\":\"Currency\"}],\"rows\":[]}}"));
        assertNull(CostManagementReader.currency("{\"properties\":{\"columns\":[{\"name\":\"Currency\"}],\"rows\":[[\"R$\"]]}}"));
    }

    @Test
    void badClusterCaIsRefusedWithTheVariableName() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> KubeClusterObserver.trustOnly("bm90IGEgY2VydA=="));
        assertEquals("Invalid environment variable: AKS_CA_CERT_B64", e.getMessage());
    }

    @Test
    void podTimesAndImageEvents() throws Exception {
        com.fasterxml.jackson.databind.JsonNode pods = dev.kmads.consignado.gateway.core.Json.MAPPER.readTree("""
            {"items":[{"metadata":{"name":"consignado-api-1","creationTimestamp":"2026-11-17T20:00:00Z"},
              "spec":{"nodeName":"n1"},
              "status":{"phase":"Running","startTime":"2026-11-17T20:00:01Z",
                "conditions":[{"type":"PodScheduled","status":"True","lastTransitionTime":"2026-11-17T20:00:01Z"},
                  {"type":"PodReadyToStartContainers","status":"True","lastTransitionTime":"2026-11-17T20:00:04Z"},
                  {"type":"Initialized","status":"True","lastTransitionTime":"2026-11-17T20:00:01Z"},
                  {"type":"ContainersReady","status":"True","lastTransitionTime":"2026-11-17T20:01:00Z"},
                  {"type":"Ready","status":"False","lastTransitionTime":"2026-11-17T20:00:01Z"}],
                "containerStatuses":[{"name":"api","image":"cr.io/consignado-api:abc123","imageID":"x","restartCount":2,
                  "state":{"running":{"startedAt":"2026-11-17T20:00:35Z"}}}]}}]}
            """);
        dev.kmads.consignado.gateway.core.ClusterView.Pod pod = KubeParser.pods(pods).get(0);
        var t = pod.times();
        assertEquals(java.time.Instant.parse("2026-11-17T20:00:01Z"), t.scheduled());
        assertEquals(java.time.Instant.parse("2026-11-17T20:00:04Z"), t.volumesReady());
        assertEquals(java.time.Instant.parse("2026-11-17T20:00:35Z"), t.containerRunning());
        assertEquals(java.time.Instant.parse("2026-11-17T20:01:00Z"), t.containersReady());
        assertNull(t.ready(), "a condition that is not True has no time");
        assertEquals(2, pod.restarts());
        assertEquals("abc123", pod.containers().get(0).imageTag());

        var pulled = KubeParser.imagePull(dev.kmads.consignado.gateway.core.Json.MAPPER.readTree("""
            {"items":[{"reason":"Scheduled","lastTimestamp":"2026-11-17T20:00:01Z"},
              {"reason":"Pulling","lastTimestamp":"2026-11-17T20:00:05Z"},
              {"reason":"Pulled","message":"Successfully pulled image in 25s","eventTime":"2026-11-17T20:00:30.123456Z"}]}
            """));
        assertFalse(pulled.alreadyPresent());
        assertEquals(java.time.Instant.parse("2026-11-17T20:00:05Z"), pulled.pullingAt());
        assertEquals(java.time.Instant.parse("2026-11-17T20:00:30.123456Z"), pulled.pulledAt());
        var present = KubeParser.imagePull(dev.kmads.consignado.gateway.core.Json.MAPPER.readTree("""
            {"items":[{"reason":"Pulled","message":"Container image x already present on machine",
              "lastTimestamp":"2026-11-17T20:00:05Z"},{"reason":"Pulled","lastTimestamp":"not a time"}]}
            """));
        assertTrue(present.alreadyPresent());
    }
}
