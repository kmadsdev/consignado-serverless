package dev.kmads.consignado.gateway.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.ClusterView;
import java.util.ArrayList;
import java.util.List;

/** Turns Kubernetes API JSON (NodeList, PodList, EndpointsList) into the plain values of the core. */
final class KubeParser {
    private KubeParser() {}

    static List<ClusterView.Node> nodes(JsonNode list) {
        List<ClusterView.Node> out = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            out.add(new ClusterView.Node(item.path("metadata").path("name").asText(""),
                    condition(item, "Ready")));
        }
        return out;
    }

    /** When the node list cannot be read: each node that holds a pod with a running container is Ready. */
    static List<ClusterView.Node> nodesFromPods(List<ClusterView.Pod> pods) {
        java.util.Map<String, Boolean> nodes = new java.util.TreeMap<>();
        for (ClusterView.Pod pod : pods) {
            if (pod.node().isEmpty()) continue;
            boolean alive = pod.containers().stream().anyMatch(ClusterView.Container::running);
            nodes.merge(pod.node(), alive, Boolean::logicalOr);
        }
        List<ClusterView.Node> out = new ArrayList<>();
        nodes.forEach((name, ready) -> out.add(new ClusterView.Node(name, ready)));
        return out;
    }

    static List<ClusterView.Pod> pods(JsonNode list) {
        List<ClusterView.Pod> out = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            if (item.path("metadata").has("deletionTimestamp")) continue;
            List<ClusterView.Container> containers = new ArrayList<>();
            for (JsonNode cs : item.path("status").path("containerStatuses")) {
                JsonNode state = cs.path("state");
                String kind = state.has("running") ? "running" : state.has("terminated") ? "terminated"
                        : state.has("waiting") ? "waiting" : "unknown";
                String reason = state.path(kind).path("reason").asText(null);
                containers.add(new ClusterView.Container(cs.path("name").asText(""), kind, reason,
                        !cs.path("imageID").asText("").isEmpty()));
            }
            out.add(new ClusterView.Pod(
                    item.path("metadata").path("name").asText(""),
                    item.path("spec").path("nodeName").asText(""),
                    item.path("status").path("phase").asText(""),
                    condition(item, "PodScheduled"),
                    condition(item, "Initialized"),
                    condition(item, "PodReadyToStartContainers"),
                    condition(item, "ContainersReady"),
                    condition(item, "Ready"),
                    containers));
        }
        return out;
    }

    /** The number of ready addresses of all Endpoints objects in the namespace. */
    static int readyEndpoints(JsonNode list) {
        int count = 0;
        for (JsonNode item : list.path("items")) {
            for (JsonNode subset : item.path("subsets")) {
                count += subset.path("addresses").size();
            }
        }
        return count;
    }

    private static boolean condition(JsonNode item, String type) {
        for (JsonNode c : item.path("status").path("conditions")) {
            if (type.equals(c.path("type").asText())) return "True".equals(c.path("status").asText());
        }
        return false;
    }
}
