package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.UserStore;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-modify-write of one users row with the ETag. A request that read the row earlier can never
 * overwrite a newer change (for example an admin block): on a conflict the row is read again and the
 * change is applied to the new row, one time.
 */
public final class UserRows {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");

    private UserRows() {}

    /** Empty when the row does not exist. 409 "busy" after a second conflict. */
    public static Optional<User> update(UserStore users, String id, Consumer<User> change) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<User> found = users.find(id);
            if (found.isEmpty()) return found;
            User u = found.get();
            change.accept(u);
            try {
                users.save(u);
                return Optional.of(u);
            } catch (UserStore.ConflictException e) {
                // Another writer was first: read the new row and apply the change again.
            }
        }
        throw ApiException.conflict("busy", "The account changed at the same time. Try again.");
    }

    /** For the timer: the same, but a second conflict is logged and not thrown. */
    public static void updateQuietly(UserStore users, String id, Consumer<User> change, String what) {
        try {
            update(users, id, change);
        } catch (ApiException e) {
            LOG.log(Level.WARNING, Logs.fmt("user row not written ({0}): conflict twice", what));
        }
    }
}
