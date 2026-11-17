package dev.kmads.consignado.gateway.ports;

import java.util.function.Supplier;

public interface Secrets {
    /** value is null when the secret does not exist. cached is true when no Key Vault call was made. */
    record Read(String value, boolean cached) {
        public boolean found() { return value != null; }
    }

    Read read(String name);

    /** Reads the secret; when it does not exist, creates it once with the generated value. */
    String readOrCreate(String name, Supplier<String> generator);
}
