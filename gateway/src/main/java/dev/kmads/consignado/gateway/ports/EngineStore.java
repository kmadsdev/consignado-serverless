package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.EngineRecord;

public interface EngineStore {
    /** Returns the engine row, or a new "stopped" record when the row does not exist. */
    EngineRecord load();
    void save(EngineRecord record);
}
