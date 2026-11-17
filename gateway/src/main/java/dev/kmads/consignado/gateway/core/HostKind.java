package dev.kmads.consignado.gateway.core;

public enum HostKind {
    FUNCTIONS("functions"), AKS("aks");

    private final String id;

    HostKind(String id) { this.id = id; }

    public String id() { return id; }

    /** Time limit of one upstream call (the adapter uses it; the trace names it on a timeout). */
    public java.time.Duration upstreamTimeout() {
        return this == FUNCTIONS ? java.time.Duration.ofSeconds(30) : java.time.Duration.ofSeconds(10);
    }
}
