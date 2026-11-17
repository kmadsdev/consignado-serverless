package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.ports.Upstream;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Contract addendum: per-user window settings (part 2) and the error trace rule (part 1, last paragraph). */
class AddendumWindowsAndTraceTest {
    private static final String ADMIN = "good-" + World.ADMIN;
    private final World w = new World();

    private GatewayResponse set(String body) {
        return w.handlers.admin(World.req("POST", "users/u1", ADMIN, null, body));
    }

    @Test
    void meAndAdminUserCarryWindowMinutes() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        assertEquals(15, World.json(w.handlers.me(World.req("GET", null, "good-u1", null, null)))
                .get("windowMinutes").asInt());
        JsonNode list = World.json(w.handlers.admin(World.req("GET", "users", ADMIN, null, null)));
        assertEquals(15, list.get(0).get("windowMinutes").asInt());
        // A row from before the column (0) shows the default.
        w.users.rows.get("u1").windowMinutes = 0;
        assertEquals(15, World.json(w.handlers.me(World.req("GET", null, "good-u1", null, null)))
                .get("windowMinutes").asInt());
    }

    @Test
    void adminSetChangesWindowsMaxAndMinutes() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        JsonNode u = World.json(set("{\"action\":\"set\",\"windowsMax\":7,\"windowMinutes\":30}"));
        assertEquals(7, u.get("windowsMax").asInt());
        assertEquals(30, u.get("windowMinutes").asInt());
        assertEquals(0, World.json(set("{\"action\":\"set\",\"windowsMax\":0}")).get("windowsMax").asInt());
        assertEquals(30, w.users.rows.get("u1").windowMinutes, "an absent field is not changed");
        assertTrue(w.audit.contains("admin.user.set"));
        for (String bad : new String[] {"{\"action\":\"set\"}", "{\"action\":\"set\",\"windowsMax\":51}",
                "{\"action\":\"set\",\"windowsMax\":-1}", "{\"action\":\"set\",\"windowMinutes\":4}",
                "{\"action\":\"set\",\"windowMinutes\":61}", "{\"action\":\"set\",\"windowMinutes\":\"20\"}",
                "{\"action\":\"set\",\"windowMinutes\":20.5}"}) {
            assertEquals(400, set(bad).status(), bad);
        }
        assertEquals(403, w.handlers.admin(World.req("POST", "users/u1", "good-u1", null,
                "{\"action\":\"set\",\"windowsMax\":50}")).status());
    }

    @Test
    void aNewWindowUsesTheOwnersMinutesAndAChangeDoesNotAlterAnOpenWindow() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        set("{\"action\":\"set\",\"windowMinutes\":5}");
        String id = World.json(w.createSession("u1")).get("id").asText();
        w.makeReady();
        JsonNode s = World.json(w.handlers.sessionsGet(World.req("GET", id, "good-u1", null, null)));
        Instant ready = Instant.parse(s.get("readyAt").asText());
        assertEquals(ready.plus(Duration.ofMinutes(5)), Instant.parse(s.get("expiresAt").asText()));
        set("{\"action\":\"set\",\"windowMinutes\":60}");
        s = World.json(w.handlers.sessionsGet(World.req("GET", id, "good-u1", null, null)));
        assertEquals(ready.plus(Duration.ofMinutes(5)), Instant.parse(s.get("expiresAt").asText()));
        w.clock.plus(Duration.ofMinutes(5));
        w.engine.tick();
        s = World.json(w.handlers.sessionsGet(World.req("GET", id, "good-u1", null, null)));
        assertEquals("ended", s.get("state").asText());
        JsonNode close = null;
        for (JsonNode e : s.get("events")) if (e.get("stage").asText().equals("window-close")) close = e;
        assertNotNull(close);
        assertEquals("the 5-minute window ended", close.get("detail").asText());
    }

    // -------------------------------------------------------------- trace rule

    private static JsonNode trace(GatewayResponse r) throws Exception {
        String h = r.headers().get("x-consignado-trace");
        assertNotNull(h, "the error answer carries the trace");
        return Json.MAPPER.readTree(Base64.getUrlDecoder().decode(h));
    }

    private static JsonNode stage(JsonNode trace, String id) {
        for (JsonNode e : trace.get("events")) if (e.get("stage").asText().equals(id)) return e;
        throw new AssertionError("no stage " + id);
    }

    private GatewayResponse preview() {
        return w.handlers.proxy(HostKind.FUNCTIONS, World.req("POST", "api/v1/simulations/preview", null, null, "{}"));
    }

    @Test
    void upstream5xxIsAnErrorStage() throws Exception {
        w.upstream.status = 503;
        GatewayResponse r = preview();
        assertEquals(503, r.status());
        JsonNode resp = stage(trace(r), "response");
        assertEquals("error", resp.get("status").asText());
        assertEquals("upstream answered 503", resp.get("detail").asText());
        w.upstream.status = 404;
        assertEquals("done", stage(trace(preview()), "response").get("status").asText(), "4xx is an answer, not a failure");
    }

    @Test
    void timeoutAndConnectionFailureAndMissingKey() throws Exception {
        w.upstream.fail = new Upstream.UpstreamException(true);
        GatewayResponse r = preview();
        assertEquals(504, r.status());
        assertEquals("timeout after 30 s", stage(trace(r), "response").get("detail").asText());
        assertEquals("error", stage(trace(r), "response").get("status").asText());
        w.upstream.fail = new Upstream.UpstreamException(false);
        assertEquals("no answer from the API host (connection failed)",
                stage(trace(preview()), "response").get("detail").asText());
        w.upstream.fail = null;
        w.vault.values.remove("apifn-host-key");
        GatewayResponse noKey = preview();
        assertEquals(502, noKey.status());
        JsonNode kv = stage(trace(noKey), "keyvault");
        assertEquals("error", kv.get("status").asText());
        assertEquals("function key not found in Key Vault", kv.get("detail").asText());
    }
}
