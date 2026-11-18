package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.EngineStore;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-modify-write of the engine row with its ETag. The change sets only the fields that its caller owns;
 * on a conflict the row is read again and the change is applied to the newer row. So a writer never
 * overwrites a field of another writer (for example a pause by the admin or the budget webhook).
 * No slow call (ARM, HTTP) may run inside the change.
 */
public final class EngineRows {
    /** The row changes a few times per minute at most: 5 attempts is a large margin. */
    public static final int ATTEMPTS = 5;
    private static final Logger LOG = Logger.getLogger("consignado.gateway");

    private EngineRows() {}

    /** Returns the written row. 409 "busy" when the row changed at each of the attempts. */
    public static EngineRecord update(EngineStore store, Consumer<EngineRecord> change) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            EngineRecord rec = store.load();
            change.accept(rec);
            try {
                store.save(rec);
                return rec;
            } catch (EngineStore.ConflictException e) {
                // Another writer was first: read the new row and apply the change again.
            }
        }
        throw ApiException.conflict("busy", "The engine changed at the same time. Try again.");
    }

    /** For the timer bookkeeping: the same, but the last conflict is logged and not thrown. */
    public static void updateQuietly(EngineStore store, Consumer<EngineRecord> change, String what) {
        try {
            update(store, change);
        } catch (ApiException e) {
            LOG.log(Level.WARNING, Logs.fmt("engine row not written ({0}): it changed {1} times", what, ATTEMPTS));
        }
    }
}
