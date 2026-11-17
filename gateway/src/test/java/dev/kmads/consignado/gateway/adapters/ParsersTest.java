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
    void costQueryResult() throws Exception {
        assertEquals(12.35, CostManagementReader.parse("{\"properties\":{\"columns\":[{\"name\":\"CostUSD\"},{\"name\":\"Currency\"}],\"rows\":[[12.3456,\"USD\"]]}}"));
        assertEquals(0.0, CostManagementReader.parse("{\"properties\":{\"rows\":[]}}"));
        assertThrows(Exception.class, () -> CostManagementReader.parse("{\"properties\":{\"rows\":[[\"USD\"]]}}"));
    }

    @Test
    void badClusterCaIsRefusedWithTheVariableName() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> KubeClusterObserver.trustOnly("bm90IGEgY2VydA=="));
        assertEquals("Invalid environment variable: AKS_CA_CERT_B64", e.getMessage());
    }
}
