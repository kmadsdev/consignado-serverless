package dev.kmads.consignado.gateway.ports;

public interface Secrets {
    /** value is null when the secret does not exist. cached is true when no Key Vault call was made. */
    record Read(String value, boolean cached) {
        public boolean found() { return value != null; }
    }

    Read read(String name);
}
