package dev.kmads.consignado.gateway.ports;

import java.util.Optional;

/** The blob data/snapshot.json. */
public interface SnapshotStore {
    Optional<String> read();
    void write(String json);
}
