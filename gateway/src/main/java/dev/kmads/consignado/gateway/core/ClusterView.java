package dev.kmads.consignado.gateway.core;

import java.util.Comparator;
import java.util.List;

/** What the Kubernetes API says about namespace consignado, in plain values. */
public record ClusterView(boolean apiServerUp, List<Node> nodes, List<Pod> pods, int readyEndpoints) {

    public static final ClusterView DOWN = new ClusterView(false, List.of(), List.of(), 0);

    public record Node(String name, boolean ready) {}

    /**
     * The pod's own times: status.startTime, lastTransitionTime of the conditions PodScheduled,
     * PodReadyToStartContainers, Initialized, ContainersReady, Ready, and the latest
     * containerStatuses[].state.running.startedAt. Null when Kubernetes did not give it.
     */
    public record PodTimes(java.time.Instant started, java.time.Instant scheduled, java.time.Instant readyToStart,
                           java.time.Instant initialized, java.time.Instant containersReady, java.time.Instant ready,
                           java.time.Instant containerRunning) {
        public static final PodTimes NONE = new PodTimes(null, null, null, null, null, null, null);

        /** Sandbox and volumes ready: PodReadyToStartContainers, or Initialized on an older Kubernetes. */
        public java.time.Instant volumesReady() {
            return readyToStart != null ? readyToStart : initialized;
        }
    }

    /** From the pod's events: "Pulling" and "Pulled" times, or the image was already on the node. */
    public record ImagePull(boolean alreadyPresent, java.time.Instant pullingAt, java.time.Instant pulledAt) {}

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

        /** The image name without registry and tag ("cr.io/consignado-api:abc123" -> "consignado-api"), or null. */
        public String imageName() {
            if (image == null) return null;
            String noDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
            int slash = noDigest.lastIndexOf('/');
            int colon = noDigest.lastIndexOf(':');
            return noDigest.substring(slash + 1, colon > slash ? colon : noDigest.length());
        }

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
                      java.time.Instant createdAt, PodTimes times) {

        public Pod(String name, String node, String phase, boolean scheduled, boolean initialized,
                   boolean readyToStart, boolean containersReady, boolean ready, List<Container> containers) {
            this(name, node, phase, scheduled, initialized, readyToStart, containersReady, ready, containers, null,
                    PodTimes.NONE);
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
