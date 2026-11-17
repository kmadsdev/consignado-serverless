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
                        !cs.path("imageID").asText("").isEmpty(), cs.path("restartCount").asInt(0),
                        cs.path("image").asText(null)));
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
                    containers,
                    dev.kmads.consignado.gateway.core.Times.parse(
                            item.path("metadata").path("creationTimestamp").asText(null)),
                    times(item)));
        }
        return out;
    }

    static List<ClusterView.Deployment> deployments(JsonNode list) {
        List<ClusterView.Deployment> out = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            out.add(new ClusterView.Deployment(item.path("metadata").path("name").asText(""),
                    item.path("spec").path("replicas").asInt(1), item.path("status").path("readyReplicas").asInt(0)));
        }
        return out;
    }

    static List<ClusterView.Hpa> autoscalers(JsonNode list) {
        List<ClusterView.Hpa> out = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            out.add(new ClusterView.Hpa(item.path("metadata").path("name").asText(""),
                    item.path("status").path("currentReplicas").asInt(0),
                    item.path("status").path("desiredReplicas").asInt(0),
                    item.path("spec").path("minReplicas").asInt(1), item.path("spec").path("maxReplicas").asInt(0)));
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

    static ClusterView.PodTimes times(JsonNode item) {
        java.time.Instant running = null;
        for (JsonNode cs : item.path("status").path("containerStatuses")) {
            java.time.Instant t = time(cs.path("state").path("running").path("startedAt").asText(null));
            if (t != null && (running == null || t.isAfter(running))) running = t;
        }
        return new ClusterView.PodTimes(time(item.path("status").path("startTime").asText(null)),
                since(item, "PodScheduled"), since(item, "PodReadyToStartContainers"), since(item, "Initialized"),
                since(item, "ContainersReady"), since(item, "Ready"), running);
    }

    /** lastTransitionTime of a condition that is True, or null. */
    private static java.time.Instant since(JsonNode item, String type) {
        for (JsonNode c : item.path("status").path("conditions")) {
            if (type.equals(c.path("type").asText()) && "True".equals(c.path("status").asText())) {
                return time(c.path("lastTransitionTime").asText(null));
            }
        }
        return null;
    }

    private static java.time.Instant time(String text) {
        try {
            return dev.kmads.consignado.gateway.core.Times.parse(text);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** EventList of one pod: the newest Pulling and Pulled events, or "already present on machine". */
    static ClusterView.ImagePull imagePull(JsonNode events) {
        boolean present = false;
        java.time.Instant pulling = null;
        java.time.Instant pulled = null;
        for (JsonNode e : events.path("items")) {
            String reason = e.path("reason").asText("");
            java.time.Instant t = time(e.path("eventTime").asText(null));
            if (t == null) t = time(e.path("lastTimestamp").asText(null));
            if (t == null) t = time(e.path("firstTimestamp").asText(null));
            if (reason.equals("Pulling") && t != null && (pulling == null || t.isAfter(pulling))) pulling = t;
            if (reason.equals("Pulled")) {
                if (e.path("message").asText("").contains("already present on machine")) present = true;
                else if (t != null && (pulled == null || t.isAfter(pulled))) pulled = t;
            }
        }
        return new ClusterView.ImagePull(present && pulled == null, pulling, pulled);
    }

    private static boolean condition(JsonNode item, String type) {
        for (JsonNode c : item.path("status").path("conditions")) {
            if (type.equals(c.path("type").asText())) return "True".equals(c.path("status").asText());
        }
        return false;
    }
}
