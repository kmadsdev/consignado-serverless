package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.User;
import java.util.List;
import java.util.Optional;

public interface UserStore {
    /** Thrown by save when the row changed after it was read (ETag). */
    final class ConflictException extends RuntimeException {
        public ConflictException() { super("user row changed"); }
    }

    Optional<User> find(String id);
    /** Writes the row. When user.etag is set, the write succeeds only if the row is unchanged; then etag is new. */
    void save(User user);
    List<User> list();
    /** Removes the row. No error when it does not exist. */
    void delete(String id);
}
