package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.fakes.World;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** EDGE=cloudflare: every route except the budget hook needs the header X-Edge-Key. */
class EdgeKeyTest {
    private final World w = new World(true, true);

    EdgeKeyTest() {
        w.vault.values.put("edge-key", "edge-secret-value");
    }

    private static GatewayRequest req(String method, String path, String edgeKey, String token, String body) {
        Map<String, String> headers = new HashMap<>();
        if (edgeKey != null) headers.put("X-Edge-Key", edgeKey);
        if (token != null) headers.put("Authorization", "Bearer " + token);
        headers.put("CF-Connecting-IP", "203.0.113.77");
        headers.put("Origin", "http://localhost:5173");
        return GatewayRequest.of(method, path, null, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertForbidden(GatewayResponse r) {
        assertEquals(403, r.status());
        assertEquals("{\"code\":\"forbidden\",\"message\":\"Not allowed\"}", new String(r.body(), StandardCharsets.UTF_8));
        assertNull(r.headers().get("Access-Control-Allow-Origin"));
    }

    @Test
    void missingOrWrongKeyIsRefusedOnEveryRouteWithNoDetail() {
        for (String key : new String[] {null, "", "wrong", "edge-secret-valuX", "edge-secret-value ", "x".repeat(600)}) {
            assertForbidden(w.handlers.health(req("GET", null, key, null, null)));
            assertForbidden(w.handlers.engine(req("GET", null, key, null, null)));
            assertForbidden(w.handlers.snapshot(req("GET", null, key, null, null)));
            assertForbidden(w.handlers.contact(req("POST", null, key, null, "{}")));
            assertForbidden(w.handlers.me(req("GET", null, key, "good-u1", null)));
            assertForbidden(w.handlers.profile(req("PUT", null, key, "good-u1", World.profileBody("fingerprint-1"))));
            assertForbidden(w.handlers.sessionsCreate(req("POST", null, key, "good-u1", null)));
            assertForbidden(w.handlers.sessionsGet(req("GET", "sess0001", key, "good-u1", null)));
            assertForbidden(w.handlers.proxy(HostKind.FUNCTIONS, req("POST", "api/v1/simulations/preview", key, null, "{}")));
            assertForbidden(w.handlers.proxy(HostKind.AKS, req("GET", "api/v1/customers", key, "good-u1", null)));
            assertForbidden(w.handlers.admin(req("GET", "users", key, "good-" + World.ADMIN, null)));
            assertForbidden(w.handlers.health(req("OPTIONS", null, key, null, null)));
        }
        assertTrue(w.users.rows.isEmpty(), "a refused request does no work: no user row");
        assertTrue(w.upstream.calls.isEmpty());
        assertTrue(w.audit.isEmpty());
    }

    @Test
    void rightKeyPasses() {
        assertEquals(200, w.handlers.health(req("GET", null, "edge-secret-value", null, null)).status());
        assertEquals(200, w.handlers.me(req("GET", null, "edge-secret-value", "good-u1", null)).status());
        assertNotNull(w.users.rows.get("u1").ipHash, "CF-Connecting-IP is used after the edge key matched");
        assertEquals(200, w.handlers.proxy(HostKind.FUNCTIONS,
                req("POST", "api/v1/simulations/preview", "edge-secret-value", null, "{}")).status());
        assertFalse(w.upstream.last().headers().containsKey("x-edge-key"), "the edge key never goes upstream");
    }

    @Test
    void secretThatCannotBeReadGives503NeverAPass() {
        w.vault.values.remove("edge-key");
        for (String key : new String[] {"edge-secret-value", "", null}) {
            GatewayResponse r = w.handlers.me(req("GET", null, key, "good-u1", null));
            assertEquals(503, r.status());
            assertEquals("unavailable", World.json(r).get("code").asText());
        }
        assertTrue(w.users.rows.isEmpty(), "no work done");
        w.vault.values.put("edge-key", "edge-secret-value");
        w.vault.down = true;
        assertEquals(503, w.handlers.engine(req("GET", null, "edge-secret-value", null, null)).status());
    }

    @Test
    void budgetHookKeepsItsFunctionKeyAndNeedsNoEdgeKey() {
        GatewayResponse r = w.handlers.budgetHook(req("POST", null, null, null, "{\"data\":{\"SpendingAmount\":61,\"Budget\":60}}"));
        assertEquals(200, r.status());
        assertTrue(w.engineRow.paused);
    }

    @Test
    void checkIsSkippedWhenTheEdgeIsNotCloudflare() {
        World local = new World(false, false);
        assertEquals(200, local.handlers.health(req("GET", null, null, null, null)).status());
    }
}
