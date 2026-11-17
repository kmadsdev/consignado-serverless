package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.User;
import dev.kmads.consignado.gateway.fakes.World;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserRoutesTest {
    private final World w = new World();

    @Test
    void healthIsUp() {
        GatewayResponse r = w.handlers.health(World.req("GET", null, null, null, null));
        assertEquals(200, r.status());
        assertEquals("UP", World.json(r).get("status").asText());
        assertEquals("no-store", r.headers().get("Cache-Control"));
    }

    @Test
    void meNeedsAToken() {
        GatewayResponse r = w.handlers.me(World.req("GET", null, null, null, null));
        assertEquals(401, r.status());
        assertEquals("unauthorized", World.json(r).get("code").asText());
    }

    @Test
    void aTokenThatDoesNotVerifyIsNeverTreatedAsAGuest() {
        assertEquals(401, w.handlers.me(World.req("GET", null, "forged", null, null)).status());
        GatewayResponse r = w.handlers.proxy(dev.kmads.consignado.gateway.core.HostKind.FUNCTIONS,
                World.req("POST", "api/v1/simulations/preview", "forged", null, "{}"));
        assertEquals(401, r.status());
        assertEquals("invalid-token", World.json(r).get("code").asText());
        GatewayRequest basic = GatewayRequest.of("GET", null, null, Map.of("Authorization", "Basic abc"), null);
        assertEquals(401, w.handlers.me(basic).status());
    }

    @Test
    void firstCallCreatesTheUserAndTellsTheOwnerOnce() {
        JsonNode me = World.json(w.handlers.me(World.req("GET", null, "good-u1", "203.0.113.1", null)));
        assertEquals("u1", me.get("id").asText());
        assertEquals("user", me.get("role").asText());
        assertEquals("active", me.get("status").asText());
        assertFalse(me.get("profileComplete").asBoolean());
        assertEquals(0, me.get("windowsUsed").asInt());
        assertEquals(3, me.get("windowsMax").asInt());
        assertFalse(me.has("activeSessionId"));
        w.handlers.me(World.req("GET", null, "good-u1", "203.0.113.1", null));
        assertEquals(1, w.mails.size());
        assertTrue(w.audit.contains("user.create"));
        User row = w.users.rows.get("u1");
        assertEquals(64, row.ipHash.length(), "the IP is stored as an HMAC-SHA256 hex value");
        assertFalse(row.ipHash.contains("203.0.113.1"));
    }

    @Test
    void missingHashKeyFailsAndStoresNothing() {
        w.vault.values.remove("hash-key");
        GatewayResponse r = w.handlers.me(World.req("GET", null, "good-u1", "203.0.113.1", null));
        assertEquals(500, r.status());
        assertEquals("internal", World.json(r).get("code").asText());
        assertTrue(w.users.rows.isEmpty(), "no user row without a hashed IP");
    }

    @Test
    void ownerHasTheAdminRole() {
        JsonNode me = World.json(w.handlers.me(World.req("GET", null, "good-" + World.ADMIN, null, null)));
        assertEquals("admin", me.get("role").asText());
    }

    @Test
    void profileIsSavedAndComplete() {
        JsonNode me = World.json(w.signUp("u1", "203.0.113.1", "fingerprint-aaa"));
        assertTrue(me.get("profileComplete").asBoolean());
        assertEquals("Ana Souza", me.get("name").asText());
        assertEquals("active", me.get("status").asText());
        User row = w.users.rows.get("u1");
        assertNotNull(row.consentAt);
        assertNotEquals("fingerprint-aaa", row.fingerprintHash);
    }

    @Test
    void sameDeviceHoldsTheSecondAccount() {
        w.signUp("u1", "203.0.113.1", "fingerprint-aaa");
        JsonNode second = World.json(w.signUp("u2", "198.51.100.7", "fingerprint-aaa"));
        assertEquals("held", second.get("status").asText());
        assertTrue(w.users.rows.get("u2").flags.contains("same-device"));
        assertEquals("active", w.users.rows.get("u1").status);
        assertTrue(w.mails.contains("Consignado: account held"));
    }

    @Test
    void sameIpOnlySetsAFlagAndTheAccountStaysActive() {
        w.signUp("u1", "203.0.113.1", "fingerprint-aaa");
        JsonNode second = World.json(w.signUp("u2", "203.0.113.1", "fingerprint-bbb"));
        assertEquals("active", second.get("status").asText());
        assertEquals(java.util.Set.of("same-ip"), w.users.rows.get("u2").flags);
    }

    @Test
    void anAccountThatTheAdminApprovedIsNotHeldAgain() {
        w.signUp("u1", "203.0.113.1", "fingerprint-aaa");
        w.signUp("u2", "198.51.100.7", "fingerprint-aaa");
        GatewayResponse approve = w.handlers.admin(World.req("POST", "users/u2", "good-" + World.ADMIN, null,
                "{\"action\":\"approve\"}"));
        assertEquals("active", World.json(approve).get("status").asText());
        assertEquals("active", World.json(w.signUp("u2", "198.51.100.7", "fingerprint-aaa")).get("status").asText());
    }

    @Test
    void profileInputIsValidated() {
        w.handlers.me(World.req("GET", null, "good-u1", null, null));
        String[] bad = {
            "{\"name\":\"A\",\"company\":\"Acme\",\"linkedin\":\"https://www.linkedin.com/in/ana\",\"consent\":true,\"fingerprint\":\"fingerprint-1\"}",
            "{\"name\":\"Ana\",\"company\":\"Acme\",\"linkedin\":\"https://evil.example/in/ana\",\"consent\":true,\"fingerprint\":\"fingerprint-1\"}",
            "{\"name\":\"Ana\",\"company\":\"Acme\",\"linkedin\":\"http://www.linkedin.com/in/ana\",\"consent\":true,\"fingerprint\":\"fingerprint-1\"}",
            "{\"name\":\"Ana\",\"company\":\"Acme\",\"linkedin\":\"https://www.linkedin.com/in/ana\",\"consent\":false,\"fingerprint\":\"fingerprint-1\"}",
            "{\"name\":\"Ana\",\"company\":\"Acme\",\"linkedin\":\"https://www.linkedin.com/in/ana\",\"consent\":true,\"fingerprint\":\"x\"}",
            "{\"name\":\"" + "n".repeat(81) + "\",\"company\":\"Acme\",\"linkedin\":\"https://www.linkedin.com/in/ana\",\"consent\":true,\"fingerprint\":\"fingerprint-1\"}",
            "[1]", "{bad json"};
        for (String body : bad) {
            GatewayResponse r = w.handlers.profile(World.req("PUT", null, "good-u1", null, body));
            assertEquals(400, r.status(), body);
            assertEquals("bad-request", World.json(r).get("code").asText());
        }
        assertEquals(400, w.handlers.profile(World.req("PUT", null, "good-u1", null, null)).status());
        String big = "{\"name\":\"" + "n".repeat(20000) + "\"}";
        assertEquals(413, w.handlers.profile(World.req("PUT", null, "good-u1", null, big)).status());
        assertFalse(w.users.rows.get("u1").profileComplete());
    }

    @Test
    void failedBotCheckRefusesTheProfile() {
        w.botOk = false;
        GatewayResponse r = w.handlers.profile(World.req("PUT", null, "good-u1", null, World.profileBody("fingerprint-1")));
        assertEquals(403, r.status());
        assertEquals("bot-check-failed", World.json(r).get("code").asText());
    }

    @Test
    void clientIpComesFromTheCloudflareHeaderOnly() {
        assertEquals("203.0.113.5", Handlers.clientIp(GatewayRequest.of("GET", null, null,
                Map.of("CF-Connecting-IP", "203.0.113.5", "X-Forwarded-For", "10.0.0.1"), null)));
        assertEquals("2001:db8::1", Handlers.clientIp(GatewayRequest.of("GET", null, null,
                Map.of("CF-Connecting-IP", "2001:db8::1"), null)));
        assertNull(Handlers.clientIp(GatewayRequest.of("GET", null, null, Map.of("CF-Connecting-IP", "<script>"), null)));
        assertNull(Handlers.clientIp(GatewayRequest.of("GET", null, null, Map.of("X-Forwarded-For", "203.0.113.5"), null)));
        assertNull(Handlers.clientIp(GatewayRequest.of("GET", null, null, Map.of("X-Azure-ClientIP", "203.0.113.5"), null)));
    }

    @Test
    void contactIsSavedMailedAndGuarded() {
        String body = "{\"kind\":\"more-windows\",\"name\":\"Ana Souza\",\"email\":\"ana@example.com\",\"message\":\"More windows, please.\",\"turnstileToken\":\"t\"}";
        GatewayResponse r = w.handlers.contact(World.req("POST", null, null, "203.0.113.1", body));
        assertEquals(201, r.status());
        JsonNode c = World.json(r);
        assertEquals("more-windows", c.get("kind").asText());
        assertTrue(c.has("id") && c.has("createdAt"));
        assertEquals(1, w.contactRows.size());
        assertTrue(w.mails.contains("Consignado: request for more windows"));
        assertTrue(w.audit.contains("contact.create"));

        assertEquals(400, w.handlers.contact(World.req("POST", null, null, "198.51.100.9", body.replace("ana@example.com", "ana@@x"))).status());
        assertEquals(400, w.handlers.contact(World.req("POST", null, null, "198.51.100.9", body.replace("more-windows", "spam"))).status());
        assertEquals(201, w.handlers.contact(World.req("POST", null, null, "203.0.113.1", body)).status());
        assertEquals(201, w.handlers.contact(World.req("POST", null, null, "203.0.113.1", body)).status());
        assertEquals(429, w.handlers.contact(World.req("POST", null, null, "203.0.113.1", body)).status());
        assertEquals(201, w.handlers.contact(World.req("POST", null, null, "198.51.100.1", body)).status());
        w.botOk = false;
        assertEquals(403, w.handlers.contact(World.req("POST", null, null, "198.51.100.2", body)).status());
    }

    @Test
    void adminRoutesNeedTheOwner() {
        w.signUp("u1", "203.0.113.1", "fingerprint-aaa");
        assertEquals(401, w.handlers.admin(World.req("GET", "users", null, null, null)).status());
        assertEquals(403, w.handlers.admin(World.req("GET", "users", "good-u1", null, null)).status());
        assertEquals(403, w.handlers.admin(World.req("POST", "pause", "good-u1", null, "{\"paused\":true}")).status());
        assertFalse(w.engineRow.paused);
    }

    @Test
    void adminReadsAndActions() {
        w.signUp("u1", "203.0.113.1", "fingerprint-aaa");
        String admin = "good-" + World.ADMIN;
        JsonNode list = World.json(w.handlers.admin(World.req("GET", "users", admin, null, null)));
        assertEquals(1, list.size());
        JsonNode u = list.get(0);
        for (String f : new String[] {"id", "email", "name", "role", "profileComplete", "status", "windowsUsed",
                "windowsMax", "company", "linkedin", "createdAt", "lastSeenAt", "flags"}) {
            assertTrue(u.has(f), f);
        }
        assertEquals(6, World.json(w.handlers.admin(World.req("POST", "users/u1", admin, null,
                "{\"action\":\"grant\",\"windows\":3}"))).get("windowsMax").asInt());
        assertEquals("blocked", World.json(w.handlers.admin(World.req("POST", "users/u1", admin, null,
                "{\"action\":\"block\"}"))).get("status").asText());
        assertEquals("held", World.json(w.handlers.admin(World.req("POST", "users/u1", admin, null,
                "{\"action\":\"hold\"}"))).get("status").asText());
        assertEquals(400, w.handlers.admin(World.req("POST", "users/u1", admin, null, "{\"action\":\"remove\"}")).status());
        assertEquals(400, w.handlers.admin(World.req("POST", "users/u1", admin, null, "{\"action\":\"grant\",\"windows\":999}")).status());
        assertEquals(404, w.handlers.admin(World.req("POST", "users/nobody", admin, null, "{\"action\":\"block\"}")).status());
        assertEquals(404, w.handlers.admin(World.req("GET", "secrets", admin, null, null)).status());
        assertTrue(w.audit.contains("admin.user.grant") && w.audit.contains("admin.user.block") && w.audit.contains("admin.read"));

        JsonNode state = World.json(w.handlers.admin(World.req("GET", "state", admin, null, null)));
        assertEquals(60.0, state.get("budget").get("limitUsd").asDouble());
        assertEquals("1970-01-01T00:00:00Z", state.get("budget").get("asOf").asText(), "unknown cost is not shown as fresh");
        w.cost = java.util.Optional.of(new dev.kmads.consignado.gateway.ports.CostReader.Cost(12.34, w.clock.now));
        state = World.json(w.handlers.admin(World.req("GET", "state", admin, null, null)));
        assertEquals(12.34, state.get("budget").get("spentUsd").asDouble());
        assertFalse(state.get("paused").asBoolean());
        assertTrue(World.json(w.handlers.admin(World.req("GET", "sessions", admin, null, null))).isArray());
        assertTrue(World.json(w.handlers.admin(World.req("GET", "contacts", admin, null, null))).isArray());
        assertEquals(400, w.handlers.admin(World.req("POST", "pause", admin, null, "{\"paused\":\"yes\"}")).status());
    }

    @Test
    void corsOnlyForTheDevOriginAndOnlyWhenEnabled() {
        GatewayRequest fromDev = GatewayRequest.of("OPTIONS", null, null, Map.of("Origin", "http://localhost:5173"), null);
        assertNull(w.handlers.health(fromDev).headers().get("Access-Control-Allow-Origin"));
        World dev = new World(true);
        GatewayResponse r = dev.handlers.health(fromDev);
        assertEquals(204, r.status());
        assertEquals("http://localhost:5173", r.headers().get("Access-Control-Allow-Origin"));
        GatewayRequest other = GatewayRequest.of("GET", null, null, Map.of("Origin", "https://evil.example"), null);
        assertNull(dev.handlers.health(other).headers().get("Access-Control-Allow-Origin"));
    }

    @Test
    void unexpectedErrorGivesAGenericBody() {
        w.vault.values.clear();
        World broken = new World();
        broken.upstream.onCall = () -> { throw new IllegalStateException("host internal-name.example secret"); };
        GatewayResponse r = broken.handlers.proxy(dev.kmads.consignado.gateway.core.HostKind.FUNCTIONS,
                World.req("POST", "api/v1/simulations/preview", null, null, "{}"));
        assertEquals(500, r.status());
        assertEquals("{\"code\":\"internal\",\"message\":\"Internal error\"}", new String(r.body(), StandardCharsets.UTF_8));
    }
}
