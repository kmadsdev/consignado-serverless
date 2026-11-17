package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.Contact;
import dev.kmads.consignado.gateway.core.Session;
import dev.kmads.consignado.gateway.fakes.World;
import org.junit.jupiter.api.Test;

/** Deletion on request, early end of a window, contact link, mail failure. */
class ComplianceRoutesTest {
    private static final String ADMIN = "good-" + World.ADMIN;
    private final World w = new World();

    private GatewayResponse admin(String path, String body) {
        return w.handlers.admin(World.req("POST", path, ADMIN, null, body));
    }

    private GatewayResponse contact(String token, String kind, String email) {
        return w.handlers.contact(World.req("POST", null, token, "203.0.113.7",
                "{\"kind\":\"" + kind + "\",\"name\":\"Ana Souza\",\"email\":\"" + email
                        + "\",\"message\":\"More windows, please\",\"turnstileToken\":\"tok\"}"));
    }

    private GatewayResponse end(String token, String id) {
        return w.handlers.sessionsEnd(World.req("POST", id, token, "203.0.113.9", null));
    }

    // ------------------------------------------------------------- deletion

    @Test
    void adminDeletesUserSessionsAndContacts() {
        w.signUp("u1", "203.0.113.1", "fp-1-aaaaaaaaaaaa");
        w.signUp("u2", "203.0.113.2", "fp-2-bbbbbbbbbbbb");
        assertEquals(201, w.createSession("u1").status());
        assertEquals(201, contact("good-u1", "more-windows", "u1@example.com").status());
        assertEquals(201, contact(null, "contact", "U1@EXAMPLE.COM").status());
        assertEquals(201, contact("good-u2", "contact", "u2@example.com").status());

        GatewayResponse r = admin("users/u1", "{\"action\":\"delete\"}");

        assertEquals(200, r.status());
        JsonNode body = World.json(r);
        assertTrue(body.get("deleted").asBoolean());
        assertEquals("delete it in the Entra admin center", body.get("entraAccount").asText());
        assertFalse(w.users.rows.containsKey("u1"));
        assertTrue(w.users.rows.containsKey("u2"));
        assertTrue(w.sessions.list().stream().noneMatch(s -> s.userId.equals("u1")));
        assertEquals(1, w.contactRows.size());
        assertEquals("u2@example.com", w.contactRows.get(0).email());
        String[] row = w.auditRows.stream().filter(a -> a[1].equals("admin.user.delete")).findFirst().orElseThrow();
        assertEquals(World.ADMIN, row[0]);
        assertTrue(row[2].matches("[0-9a-f]{64}"));
        assertFalse(String.join(",", row[0], row[1], row[2]).contains("u1"));
    }

    @Test
    void deleteRules() {
        w.signUp("u1", "203.0.113.1", "fp-1-aaaaaaaaaaaa");
        w.handlers.me(World.req("GET", null, ADMIN, null, null));
        assertEquals(403, w.handlers.admin(World.req("POST", "users/u1", "good-u1", null,
                "{\"action\":\"delete\"}")).status());
        GatewayResponse self = admin("users/" + World.ADMIN, "{\"action\":\"delete\"}");
        assertEquals(409, self.status());
        assertEquals("self-delete", World.json(self).get("code").asText());
        assertTrue(w.users.rows.containsKey(World.ADMIN));
        assertEquals(404, admin("users/nobody", "{\"action\":\"delete\"}").status());
        assertTrue(w.users.rows.containsKey("u1"));
    }

    @Test
    void deletingAUserWithAnOpenWindowLetsTheTimerStopTheCluster() {
        w.signUp("u1", "203.0.113.1", "fp-1-aaaaaaaaaaaa");
        w.createSession("u1");
        w.makeReady();
        assertEquals(200, admin("users/u1", "{\"action\":\"delete\"}").status());
        w.engine.tick();
        assertEquals(1, w.cluster.stops);
    }

    // ---------------------------------------------------------- early end

    @Test
    void ownerEndsAReadyWindowEarlyAndTheTimerStopsTheCluster() {
        w.signUp("u1", "203.0.113.1", "fp-1-aaaaaaaaaaaa");
        String id = World.json(w.createSession("u1")).get("id").asText();
        w.makeReady();
        assertEquals(1, w.users.rows.get("u1").windowsUsed);

        GatewayResponse r = end("good-u1", id);

        assertEquals(200, r.status());
        JsonNode s = World.json(r);
        assertEquals("ended", s.get("state").asText());
        assertEquals("ended early by the user", s.get("events").get(s.get("events").size() - 1).get("detail").asText());
        assertEquals(1, w.users.rows.get("u1").windowsUsed, "the window is not given back");
        assertNull(w.users.rows.get("u1").activeSessionId);
        assertTrue(w.audit.contains("session.end"));
        assertEquals(0, w.cluster.stops, "the stop is left to the timer");
        w.engine.tick();
        assertEquals(1, w.cluster.stops);
        assertEquals(403, w.handlers.proxy(dev.kmads.consignado.gateway.core.HostKind.AKS,
                World.req("GET", "api/v1/customers", "good-u1", null, null)).status());
    }

    @Test
    void endingAStartingWindowCountsIt() {
        w.signUp("u1", "203.0.113.1", "fp-1-aaaaaaaaaaaa");
        String id = World.json(w.createSession("u1")).get("id").asText();
        assertEquals(0, w.users.rows.get("u1").windowsUsed);
        assertEquals("ended", World.json(end("good-u1", id)).get("state").asText());
        assertEquals(1, w.users.rows.get("u1").windowsUsed);
        // A second end changes nothing and counts nothing.
        assertEquals(200, end("good-u1", id).status());
        assertEquals(1, w.users.rows.get("u1").windowsUsed);
        // The timer does not open an ended window later.
        w.makeReady();
        assertEquals(Session.ENDED, w.sessions.find(id).orElseThrow().state);
        assertEquals(1, w.users.rows.get("u1").windowsUsed);
    }

    @Test
    void endRules() {
        w.signUp("u1", "203.0.113.1", "fp-1-aaaaaaaaaaaa");
        w.signUp("u2", "203.0.113.2", "fp-2-bbbbbbbbbbbb");
        String id = World.json(w.createSession("u1")).get("id").asText();
        assertEquals(401, end(null, id).status());
        assertEquals(404, end("good-u2", id).status());
        assertEquals(404, end("good-u1", "nosuchsession0000").status());
        GatewayResponse byOwner = end(ADMIN, id);
        assertEquals(200, byOwner.status());
        JsonNode s = World.json(byOwner);
        assertEquals("ended early by the owner",
                s.get("events").get(s.get("events").size() - 1).get("detail").asText());
    }

    // ------------------------------------------------------------- contact

    @Test
    void moreWindowsFromASignedInUserIsLinkedAndListed() {
        w.signUp("u1", "203.0.113.1", "fp-1-aaaaaaaaaaaa");
        assertEquals(201, contact("good-u1", "more-windows", "u1@example.com").status());
        assertEquals(201, contact(null, "contact", "guest@example.com").status());
        JsonNode list = World.json(w.handlers.admin(World.req("GET", "contacts", ADMIN, null, null)));
        assertEquals(2, list.size());
        JsonNode linked = null;
        for (JsonNode c : list) if (c.get("kind").asText().equals("more-windows")) linked = c;
        assertNotNull(linked);
        assertEquals("u1", linked.get("userId").asText());
        Contact guest = w.contactRows.stream().filter(c -> c.kind().equals("contact")).findFirst().orElseThrow();
        assertNull(guest.userId());
    }

    // ---------------------------------------------------------------- mail

    @Test
    void aFailedMailNeverFailsTheRequest() {
        w.mailFails = true;
        GatewayResponse me = w.handlers.me(World.req("GET", null, "good-u9", "203.0.113.9", null));
        assertEquals(200, me.status());
        assertTrue(w.users.rows.containsKey("u9"));
        assertEquals(1, w.audit.stream().filter("mail.failed"::equals).count());
        assertTrue(w.mails.isEmpty());
        assertEquals(201, contact(null, "contact", "guest@example.com").status());
        assertEquals(2, w.audit.stream().filter("mail.failed"::equals).count());
    }

    @Test
    void aSentMailIsAudited() {
        w.handlers.me(World.req("GET", null, "good-u9", "203.0.113.9", null));
        assertEquals(1, w.mails.size());
        assertTrue(w.audit.contains("mail.sent"));
    }
}
