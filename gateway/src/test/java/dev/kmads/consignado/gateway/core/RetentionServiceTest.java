package dev.kmads.consignado.gateway.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.kmads.consignado.gateway.fakes.World;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionServiceTest {
    private final World w = new World();

    private User user(String id, Instant createdAt, Instant lastSeenAt) {
        User u = new User();
        u.id = id;
        u.email = id + "@example.com";
        u.createdAt = createdAt;
        u.lastSeenAt = lastSeenAt;
        u.windowsMax = 3;
        w.users.save(u);
        return u;
    }

    private Instant monthsAgo(int months) {
        return w.clock.now.atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
    }

    @Test
    void runsAtMostOncePerDayAndKeepsTheTimeInTheEngineRow() {
        assertNotNull(w.retention.runIfDue());
        assertEquals(w.clock.now, w.engineRow.lastRetentionAt);
        w.clock.plus(Duration.ofHours(23));
        assertNull(w.retention.runIfDue());
        w.clock.plus(Duration.ofHours(1));
        assertNotNull(w.retention.runIfDue());
        assertEquals(w.clock.now, w.engineRow.lastRetentionAt);
    }

    @Test
    void theTimerRunsTheJob() {
        w.handlers.reaper();
        assertEquals(w.clock.now, w.engineRow.lastRetentionAt);
    }

    @Test
    void deletesAuditRowsOlderThan90Days() {
        LocalDate today = w.clock.now.atOffset(ZoneOffset.UTC).toLocalDate();
        w.auditDays.addAll(List.of(today.minusDays(200), today.minusDays(91), today.minusDays(90), today.minusDays(1),
                today));
        RetentionService.Result r = w.retention.runIfDue();
        assertEquals(2, r.auditRows());
        assertEquals(List.of(today.minusDays(90), today.minusDays(1), today), w.auditDays);
    }

    @Test
    void deletesAccountsWithNoActivityFor12Months() {
        user("old", monthsAgo(20), monthsAgo(13));
        user("never-seen", monthsAgo(13), null);
        user("recent", monthsAgo(20), monthsAgo(11));
        User owner = user(World.ADMIN, monthsAgo(30), monthsAgo(24));
        Session s = new Session();
        s.id = "sold";
        s.userId = "old";
        s.state = Session.ENDED;
        s.requestedAt = monthsAgo(13);
        s.open = false;
        w.sessions.save(s);
        w.contactRows.add(new Contact("c1", "contact", "Old", "OLD@example.com", "hi", "2025-01-01T00:00:00Z", null));
        w.contactRows.add(new Contact("c2", "more-windows", "Old", "x@example.com", "more", "2025-01-01T00:00:00Z", "old"));
        w.contactRows.add(new Contact("c3", "contact", "Recent", "recent@example.com", "hi", "2025-01-01T00:00:00Z", null));

        RetentionService.Result r = w.retention.runIfDue();

        assertEquals(2, r.accounts());
        assertFalse(w.users.rows.containsKey("old"));
        assertFalse(w.users.rows.containsKey("never-seen"));
        assertTrue(w.users.rows.containsKey("recent"));
        assertTrue(w.users.rows.containsKey(owner.id));
        assertFalse(w.sessions.has("sold"));
        assertEquals(List.of("c3"), w.contactRows.stream().map(Contact::id).toList());
        List<String[]> rows = w.auditRows.stream().filter(a -> a[1].equals("retention-delete")).toList();
        assertEquals(2, rows.size());
        for (String[] a : rows) {
            assertEquals("system", a[0]);
            assertTrue(a[2].matches("[0-9a-f]{64}"), "target is a hash");
            assertNull(a[3]);
        }
        assertNotEquals(rows.get(0)[2], rows.get(1)[2]);
    }

    @Test
    void keepsAnInactiveAccountWithAnActiveWindow() {
        User u = user("busy", monthsAgo(20), monthsAgo(13));
        Session s = new Session();
        s.id = "sbusy";
        s.userId = "busy";
        s.state = Session.READY;
        s.requestedAt = w.clock.now;
        w.sessions.save(s);
        u.activeSessionId = "sbusy";
        assertEquals(0, w.retention.runIfDue().accounts());
        assertTrue(w.users.rows.containsKey("busy"));
    }

    @Test
    void deletesNothingWhenTheHashKeyIsMissing() {
        w.vault.values.remove(Hasher.KEY_NAME);
        user("old", monthsAgo(20), monthsAgo(13));
        assertEquals(0, w.retention.runIfDue().accounts());
        assertTrue(w.users.rows.containsKey("old"));
    }
}
