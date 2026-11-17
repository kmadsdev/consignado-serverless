package dev.kmads.consignado.gateway.core;

import java.util.Comparator;
import java.util.List;

/** What the Kubernetes API says about namespace consignado, in plain values. */
public record ClusterView(boolean apiServerUp, List<Node> nodes, List<Pod> pods, int readyEndpoints) {

    public static final ClusterView DOWN = new ClusterView(false, List.of(), List.of(), 0);

    public record Node(String name, boolean ready) {}

    /** Deployments and autoscalers of the namespace (status page only). */
    public record Workload(List<Deployment> deployments, List<Hpa> autoscalers) {}

    public record Deployment(String name, int desired, int ready) {}

    public record Hpa(String name, int current, int desired, int min, int max) {}

    public record Container(String name, String state, String reason, boolean imagePresent, int restarts,
                            String image) {
        public Container(String name, String state, String reason, boolean imagePresent) {
            this(name, state, reason, imagePresent, 0, null);
        }

        public boolean running() { return "running".equals(state); }

        /** The tag of the image ("consignado-api:abc123" -> "abc123"), or null. */
        public String imageTag() {
            if (image == null) return null;
            int slash = image.lastIndexOf('/');
            int colon = image.lastIndexOf(':');
            return colon > slash ? image.substring(colon + 1) : null;
        }
    }

    public record Pod(String name, String node, String phase, boolean scheduled, boolean initialized,
                      boolean readyToStart, boolean containersReady, boolean ready, List<Container> containers,
                      java.time.Instant createdAt) {

        public Pod(String name, String node, String phase, boolean scheduled, boolean initialized,
                   boolean readyToStart, boolean containersReady, boolean ready, List<Container> containers) {
            this(name, node, phase, scheduled, initialized, readyToStart, containersReady, ready, containers, null);
        }

        public int restarts() {
            return containers.stream().mapToInt(Container::restarts).sum();
        }

        public boolean imagePulled() {
            return !containers.isEmpty() && containers.stream().allMatch(c -> c.imagePresent() || c.running());
        }

        public boolean volumesMounted() {
            return readyToStart || imagePulled();
        }

        public boolean containersStarted() {
            return !containers.isEmpty() && containers.stream().allMatch(Container::running);
        }

        /** The waiting reason of the first container that waits, or null. */
        public String waitingReason() {
            return containers.stream().filter(c -> "waiting".equals(c.state()) && c.reason() != null)
                    .map(Container::reason).findFirst().orElse(null);
        }

        public String firstContainer() {
            return containers.isEmpty() ? "container" : containers.get(0).name();
        }

        int progress() {
            if (ready) return 5;
            if (containersStarted()) return 4;
            if (imagePulled()) return 3;
            if (scheduled) return 2;
            return 1;
        }
    }

    public Node readyNode() {
        return nodes.stream().filter(Node::ready).min(Comparator.comparing(Node::name)).orElse(null);
    }

    /** The pod that is the furthest in its start. Pods that finished or failed are ignored. */
    public Pod bestPod() {
        return pods.stream()
                .filter(p -> !"Succeeded".equals(p.phase()) && !"Failed".equals(p.phase()))
                .max(Comparator.comparingInt(Pod::progress).thenComparing(Pod::name, Comparator.reverseOrder()))
                .orElse(null);
    }
}
