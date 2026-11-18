package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.EngineRecord;

/**
 * The single engine row. It is written with its ETag, like the users and sessions rows: the gateway can run
 * more than one instance, and the timer, the admin and the budget webhook all write this row.
 */
public interface EngineStore {
    /** Returns the engine row with its ETag, or a new "stopped" record (etag null) when the row does not exist. */
    EngineRecord load();

    /**
     * Writes the row only when it did not change since the load (If-Match with record.etag; a record without an
     * ETag creates the row and fails when it exists). Sets record.etag to the new ETag.
     * A newer row: ConflictException, and nothing is written.
     */
    void save(EngineRecord record);

    final class ConflictException extends RuntimeException {
        public ConflictException() { super("engine row changed"); }
    }
}
