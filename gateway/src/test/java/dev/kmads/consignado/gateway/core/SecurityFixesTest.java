package dev.kmads.consignado.gateway.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.http.GatewayResponse;
import dev.kmads.consignado.gateway.ports.CostReader;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Findings M1, M2/M3, L1, L12 of the security review (2026-11-17). */
class SecurityFixesTest {
    private final World w = new World();

    // ------------------------------------------------------------------ M1

    @Test
    void mailboxIsNormalised() {
        assertEquals("me@gmail.com", Validate.mailbox("M.e+1@GMail.com "));
        assertEquals("me@gmail.com", Validate.mailbox("me@googlemail.com"));
        assertEquals("m.e@example.com", Validate.mailbox("M.E+work@Example.COM"));
        assertEquals("", Validate.mailbox(null));
        assertEquals("plain", Validate.mailbox("plain"));
    }

    private void signIn(String oid, String email, String ip, String fingerprint) {
        w.handlers.me(World.req("GET", null, "good-" + oid, ip, null));
        // The fake token puts <oid>@example.com; the real one has the address that Entra ID verified.
        w.users.rows.get(oid).email = email;
        w.handlers.profile(World.req("PUT", null, "good-" + oid, ip, World.profileBody(fingerprint)));
    }

    @Test
    void sameMailboxWithAnotherFingerprintIsHeld() {
        signIn("u1", "me@gmail.com", "203.0.113.1", "fp-aaaaaaaaaaaa");
        signIn("u2", "m.e+2@gmail.com", "203.0.113.2", "fp-bbbbbbbbbbbb");
        User second = w.users.rows.get("u2");
        assertEquals(User.HELD, second.status);
        assertTrue(second.flags.contains(UserService.FLAG_SAME_MAILBOX));
        assertFalse(second.flags.contains(UserService.FLAG_SAME_DEVICE));
        assertTrue(second.mailboxHash.matches("[0-9a-f]{64}"), "only the HMAC is stored");
        assertNotEquals(Validate.mailbox(second.email), second.mailboxHash);
        assertEquals(User.ACTIVE, w.users.rows.get("u1").status);
        assertTrue(w.mails.contains("Consignado: account held"));
        assertEquals(409, w.createSession("u2").status());
    }

    @Test
    void anOldRowWithoutTheHashIsStillCompared() {
        signIn("u1", "me@example.com", "203.0.113.1", "fp-aaaaaaaaaaaa");
        w.users.rows.get("u1").mailboxHash = null;
        signIn("u2", "ME+x@example.com", "203.0.113.2", "fp-bbbbbbbbbbbb");
        assertEquals(User.HELD, w.users.rows.get("u2").status);
    }

    @Test
    void differentMailboxesAreNotHeldAndAnApprovedAccountStaysApproved() {
        signIn("u1", "ana@example.com", "203.0.113.1", "fp-aaaaaaaaaaaa");
        signIn("u2", "bia@example.com", "203.0.113.2", "fp-bbbbbbbbbbbb");
        assertEquals(User.ACTIVE, w.users.rows.get("u2").status);
        signIn("u3", "ana+3@example.com", "203.0.113.3", "fp-cccccccccccc");
        assertEquals(User.HELD, w.users.rows.get("u3").status);
        w.handlers.admin(World.req("POST", "users/u3", "good-" + World.ADMIN, null, "{\"action\":\"approve\"}"));
        w.handlers.profile(World.req("PUT", null, "good-u3", "203.0.113.3", World.profileBody("fp-cccccccccccc")));
        assertEquals(User.ACTIVE, w.users.rows.get("u3").status);
    }

    @Test
    void dailyLimitOverAllAccounts() {
        for (int i = 0; i < 12; i++) {
            Session s = new Session();
            s.id = "old" + i;
            s.userId = "x" + i;
            s.state = Session.ENDED;
            s.open = false;
            s.requestedAt = w.clock.now.minus(Duration.ofHours(23));
            w.sessions.save(s);
        }
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        GatewayResponse r = w.createSession("u1");
        assertEquals(409, r.status());
        assertEquals("daily-limit", World.json(r).get("code").asText());
        assertEquals(201, w.createSession(World.ADMIN).status(), "the owner is not limited");
        w.clock.plus(Duration.ofHours(2));
        // The 12 old windows are now 25 hours old: the rolling 24-hour count is 1 (the owner's window).
        assertEquals(201, w.createSession("u1").status());
    }

    // ------------------------------------------------------------------ L1

    @Test
    void anAdminBlockIsNotOverwrittenByAStaleRequest() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        User stale = w.users.find("u1").orElseThrow();
        w.handlers.admin(World.req("POST", "users/u1", "good-" + World.ADMIN, null, "{\"action\":\"block\"}"));
        w.cluster.running();
        w.engine.requestWindow(stale, false, 1, 1, true, null);
        User now = w.users.rows.get("u1");
        assertEquals(User.BLOCKED, now.status, "the block stays");
        assertNotNull(now.activeSessionId);
    }

    @Test
    void aConflictIsRetriedOnceOnTheNewRow() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        // Another writer grants windows between the read and the write of the admin "hold".
        w.users.beforeSave = () -> {
            User other = w.users.find("u1").orElseThrow();
            other.windowsMax = 9;
            w.users.save(other);
        };
        GatewayResponse r = w.handlers.admin(World.req("POST", "users/u1", "good-" + World.ADMIN, null,
                "{\"action\":\"hold\"}"));
        assertEquals(200, r.status());
        assertEquals(User.HELD, w.users.rows.get("u1").status);
        assertEquals(9, w.users.rows.get("u1").windowsMax, "the other change stays too");
    }

    @Test
    void aSecondConflictGives409Busy() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        Runnable[] again = new Runnable[1];
        again[0] = () -> {
            User other = w.users.find("u1").orElseThrow();
            other.windowsMax++;
            w.users.save(other);
            w.users.beforeSave = again[0];
        };
        w.users.beforeSave = again[0];
        GatewayResponse r = w.handlers.admin(World.req("POST", "users/u1", "good-" + World.ADMIN, null,
                "{\"action\":\"block\"}"));
        assertEquals(409, r.status());
        assertEquals("busy", World.json(r).get("code").asText());
        w.users.beforeSave = () -> { };
    }

    // --------------------------------------------------------------- M2/M3

    @Test
    void timerBudgetGuardPausesAtTheLimitAtMostEvery30Minutes() {
        w.cost = Optional.of(new CostReader.Cost(59.99, w.clock.now));
        w.handlers.reaper();
        assertFalse(w.engineRow.paused);
        assertEquals(w.clock.now, w.engineRow.lastBudgetCheckAt);
        w.cost = Optional.of(new CostReader.Cost(60.0, w.clock.now));
        w.clock.plus(Duration.ofMinutes(29));
        w.handlers.reaper();
        assertFalse(w.engineRow.paused, "not checked again before 30 minutes");
        w.clock.plus(Duration.ofMinutes(1));
        w.handlers.reaper();
        assertTrue(w.engineRow.paused);
        assertEquals("budget", w.engineRow.pausedReason);
        assertTrue(w.audit.contains("budget.limit.reached"));
    }

    @Test
    void unknownCostChangesNothing() {
        w.cost = Optional.empty();
        w.handlers.reaper();
        assertFalse(w.engineRow.paused);
    }

    @Test
    void guestPreviewIsRefusedWhilePausedForBudget() {
        String preview = "{\"principal\":1000,\"termMonths\":12,\"monthlyInterestRate\":0.02}";
        assertEquals(200, w.handlers.proxy(HostKind.FUNCTIONS,
                World.req("POST", "api/v1/simulations/preview", null, null, preview)).status());
        w.engine.budgetExceeded();
        int calls = w.upstream.calls.size();
        GatewayResponse r = w.handlers.proxy(HostKind.FUNCTIONS,
                World.req("POST", "api/v1/simulations/preview", null, null, preview));
        assertEquals(503, r.status());
        assertEquals("paused", World.json(r).get("code").asText());
        assertEquals(calls, w.upstream.calls.size(), "nothing went upstream");
        w.engine.pause(true, "admin", World.ADMIN, null);
        assertEquals(200, w.handlers.proxy(HostKind.FUNCTIONS,
                World.req("POST", "api/v1/simulations/preview", null, null, preview)).status(),
                "an admin pause does not stop guests");
    }

    // ----------------------------------------------------------------- L12

    @Test
    void onlyTheOwnerWritesAgreements() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        String body = "{\"name\":\"Anything\"}";
        for (String path : new String[] {"api/v1/agreements", "/api/v1/agreements", "api/v1/agreements/7"}) {
            for (String method : new String[] {"POST", "PATCH"}) {
                GatewayResponse r = w.handlers.proxy(HostKind.FUNCTIONS, World.req(method, path, "good-u1", null, body));
                assertEquals(403, r.status(), method + " " + path);
                assertEquals("forbidden", World.json(r).get("code").asText());
            }
        }
        assertEquals(200, w.handlers.proxy(HostKind.FUNCTIONS,
                World.req("GET", "api/v1/agreements", "good-u1", null, null)).status());
        assertEquals(200, w.handlers.proxy(HostKind.FUNCTIONS,
                World.req("POST", "api/v1/customers", "good-u1", null, body)).status());
        assertEquals(200, w.handlers.proxy(HostKind.FUNCTIONS,
                World.req("POST", "api/v1/agreements", "good-" + World.ADMIN, null, body)).status());
        // Same rule on the AKS host (checked before the window rule would refuse anyway).
        assertEquals(403, w.handlers.proxy(HostKind.AKS,
                World.req("POST", "api/v1/agreements", "good-u1", null, body)).status());
    }
}
