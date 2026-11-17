package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.Session;
import java.util.List;
import java.util.Optional;

public interface SessionStore {
    /** Thrown by save when another writer changed the row after it was read. */
    final class ConflictException extends RuntimeException {
        public ConflictException() { super("session row changed"); }
    }

    Optional<Session> find(String id);
    /** Writes the row. When session.etag is set, the write succeeds only if the row is unchanged. */
    void save(Session session);
    /** Sessions with open=true (starting, ready, or ended but the cluster did not stop yet). */
    List<Session> listOpen();
    List<Session> list();
}
