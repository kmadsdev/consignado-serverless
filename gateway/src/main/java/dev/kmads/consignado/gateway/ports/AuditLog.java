package dev.kmads.consignado.gateway.ports;

public interface AuditLog {
    /** Writes one security log row. It must never throw: a failed audit write is logged only. */
    void record(String who, String action, String target, String ipHash);
}
