package dev.kmads.consignado.gateway.core;

public enum HostKind {
    FUNCTIONS("functions"), AKS("aks");

    private final String id;

    HostKind(String id) { this.id = id; }

    public String id() { return id; }
}
