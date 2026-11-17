package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.AuditStore;
import dev.kmads.consignado.gateway.ports.EngineStore;
import dev.kmads.consignado.gateway.ports.SessionStore;
import dev.kmads.consignado.gateway.ports.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The retention rule of the privacy page, run by the timer at most once per day
 * (the time of the last run is in the engine row, column lastRetentionAt):
 * - audit rows older than 90 days are deleted;
 * - an account with no activity for 12 months (lastSeenAt, or createdAt when lastSeenAt is empty) is deleted:
 *   its users row, sessions rows and contacts rows. One audit row per account, without personal data:
 *   who "system", action "retention-delete", target = HMAC of the user id.
 * The owner account and an account with an active window are never deleted by this job.
 */
public final class RetentionService {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    public static final int AUDIT_DAYS = 90;
    public static final int INACTIVE_MONTHS = 12;
    public static final Duration EVERY = Duration.ofDays(1);

    public record Result(int auditRows, int accounts) {}

    private final UserStore users;
    private final SessionStore sessions;
    private final UserErasure erasure;
    private final AuditStore auditStore;
    private final AuditLog audit;
    private final EngineStore engine;
    private final Hasher hasher;
    private final Settings settings;
    private final Clock clock;

    public RetentionService(UserStore users, SessionStore sessions, UserErasure erasure, AuditStore auditStore,
                            AuditLog audit, EngineStore engine, Hasher hasher, Settings settings, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.erasure = erasure;
        this.auditStore = auditStore;
        this.audit = audit;
        this.engine = engine;
        this.hasher = hasher;
        this.settings = settings;
        this.clock = clock;
    }

    /** Runs the job when the last run is 24 hours old or more. Returns null when it did not run. */
    public Result runIfDue() {
        Instant now = clock.instant();
        EngineRecord rec = engine.load();
        if (rec.lastRetentionAt != null && Duration.between(rec.lastRetentionAt, now).compareTo(EVERY) < 0) {
            return null;
        }
        // The time is written first: a job that fails is tried again the next day, not each minute.
        rec.lastRetentionAt = now;
        engine.save(rec);
        Result result = run(now);
        LOG.log(Level.INFO, Logs.fmt("retention: {0} audit rows deleted, {1} inactive accounts deleted",
                result.auditRows(), result.accounts()));
        return result;
    }

    Result run(Instant now) {
        LocalDate firstKeptDay = now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(AUDIT_DAYS);
        int auditRows = auditStore.deleteBefore(firstKeptDay);
        Instant inactiveBefore = now.atOffset(ZoneOffset.UTC).minusMonths(INACTIVE_MONTHS).toInstant();
        int accounts = 0;
        for (User user : users.list()) {
            if (user.id.equals(settings.adminOid())) continue;
            Instant last = user.lastSeenAt != null ? user.lastSeenAt : user.createdAt;
            if (last == null || !last.isBefore(inactiveBefore)) continue;
            if (hasActiveWindow(user)) continue;
            try {
                // The hash first: when Key Vault does not answer, nothing is deleted and nothing is lost.
                String target = hasher.hash(user.id);
                erasure.erase(user);
                audit.record("system", "retention-delete", target, null);
                accounts++;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, Logs.fmt("retention: account delete failed: {0}", Logs.of(e)));
            }
        }
        return new Result(auditRows, accounts);
    }

    private boolean hasActiveWindow(User user) {
        if (user.activeSessionId == null) return false;
        return sessions.find(user.activeSessionId).map(Session::active).orElse(false);
    }
}
