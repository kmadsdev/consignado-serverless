package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.fakes.World;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M2: the optimistic concurrency of the API (ETag / If-Match, ADR 0003) works through the gateway. */
class VersionChecksTest {
    private final World w = new World();

    private GatewayResponse call(HostKind host, String method, String path, Map<String, String> extra, String body) {
        Map<String, String> headers = new HashMap<>(extra);
        headers.put("Authorization", "Bearer good-u1");
        headers.put("CF-Connecting-IP", "203.0.113.1");
        return w.handlers.proxy(host, GatewayRequest.of(method, path, null, headers,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
    }

    private GatewayResponse fn(String method, String path, Map<String, String> extra, String body) {
        return call(HostKind.FUNCTIONS, method, path, extra, body);
    }

    private void openAksWindow() {
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        w.createSession("u1");
        w.makeReady();
    }

    @Test
    void ifMatchAndIfNoneMatchGoUpstream() {
        fn("POST", "api/v1/contracts/c1/approve", Map.of("If-Match", "\"3\""), null);
        assertEquals("\"3\"", w.upstream.last().headers().get("if-match"));
        fn("GET", "api/v1/contracts/c1", Map.of("If-None-Match", "\"3\""), null);
        assertEquals("\"3\"", w.upstream.last().headers().get("if-none-match"));
    }

    @Test
    void ifMatchGoesToTheAksHostToo() {
        openAksWindow();
        GatewayResponse r = call(HostKind.AKS, "POST", "api/v1/contracts/c1/settle", Map.of("If-Match", "\"7\""), null);
        assertEquals(200, r.status());
        assertEquals("\"7\"", w.upstream.last().headers().get("if-match"));
    }

    @Test
    void etagOfTheApiReachesTheBrowserAndIsExposed() {
        w.upstream.etag = "\"4\"";
        GatewayResponse r = fn("GET", "api/v1/contracts/c1", Map.of(), null);
        assertEquals("\"4\"", r.headers().get("ETag"));
        String exposed = r.headers().get("Access-Control-Expose-Headers");
        assertTrue(exposed.contains("etag") && exposed.contains("location"), exposed);
        assertTrue(exposed.contains("x-consignado-trace"), "the old exposed headers stay");
        assertEquals("no-store", r.headers().get("Cache-Control"), "an ETag never makes an API answer cacheable");

        w.upstream.etag = "W/\"5\"";
        assertEquals("W/\"5\"", fn("GET", "api/v1/contracts/c1", Map.of(), null).headers().get("ETag"));
    }

    @Test
    void anEtagThatIsNotAnEntityTagIsDropped() {
        for (String bad : new String[] {"4", "\"a\"b\"", "\"" + "x".repeat(200) + "\"", "\"4\"\r\nSet-Cookie: x=1"}) {
            w.upstream.etag = bad;
            assertNull(fn("GET", "api/v1/contracts/c1", Map.of(), null).headers().get("ETag"), bad);
        }
    }

    @Test
    void locationIsRewrittenToThePublicPathOfTheHost() {
        w.upstream.status = 201;
        w.upstream.location = "/api/v1/contracts/c9";
        GatewayResponse r = fn("POST", "api/v1/contracts", Map.of("Idempotency-Key", "key-12345"), "{}");
        assertEquals(201, r.status());
        assertEquals("/api/fn/api/v1/contracts/c9", r.headers().get("Location"));

        openAksWindow();
        w.upstream.status = 201;
        w.upstream.location = "/api/v1/contracts/c9";
        GatewayResponse aks = call(HostKind.AKS, "POST", "api/v1/contracts", Map.of("Idempotency-Key", "key-12345"), "{}");
        assertEquals("/api/aks/api/v1/contracts/c9", aks.headers().get("Location"));
    }

    @Test
    void aLocationOutsideTheApiIsDropped() {
        for (String bad : new String[] {"/actuator/env", "/api/v1/../actuator", "//evil.example/api/v1/x",
                "/api/v1//x"}) {
            w.upstream.location = bad;
            assertNull(fn("GET", "api/v1/contracts/c1", Map.of(), null).headers().get("Location"), bad);
        }
    }

    @Test
    void noOtherUpstreamHeaderIsPassed() {
        w.upstream.etag = "\"1\"";
        GatewayResponse r = fn("GET", "api/v1/contracts/c1", Map.of(), null);
        for (String name : r.headers().keySet()) {
            assertTrue(java.util.Set.of("Content-Type", "ETag", "x-consignado-host", "x-consignado-trace",
                    "Access-Control-Expose-Headers", "Cache-Control", "X-Content-Type-Options").contains(name), name);
        }
    }

    @Test
    void preconditionFailedAndConflictPassThroughWithBodyAndTrace() {
        String body = "{\"code\":\"precondition-failed\",\"message\":\"stale\"}";
        w.upstream.status = 412;
        w.upstream.body = body.getBytes(StandardCharsets.UTF_8);
        GatewayResponse r = fn("POST", "api/v1/contracts/c1/activate", Map.of("If-Match", "\"1\""), null);
        assertEquals(412, r.status());
        assertEquals(body, new String(r.body(), StandardCharsets.UTF_8));
        assertNotNull(r.headers().get("x-consignado-trace"));

        w.upstream.status = 409;
        w.upstream.body = "{\"code\":\"concurrent-update\"}".getBytes(StandardCharsets.UTF_8);
        GatewayResponse c = fn("POST", "api/v1/contracts/c1/cancel", Map.of("If-Match", "\"1\""), "{\"reason\":\"x\"}");
        assertEquals(409, c.status());
        assertEquals("concurrent-update", World.json(c).get("code").asText());
        assertNotNull(c.headers().get("x-consignado-trace"));
    }

    @Test
    void contractStateChangesRequireIfMatch() {
        int calls = w.upstream.calls.size();
        for (String action : new String[] {"approve", "reject", "activate", "settle", "cancel"}) {
            GatewayResponse r = fn("POST", "api/v1/contracts/c1/" + action, Map.of(), "{}");
            assertEquals(428, r.status(), action);
            assertEquals("precondition-required", World.json(r).get("code").asText());
            assertEquals(428, fn("POST", "api/v1/contracts/c1/" + action, Map.of("If-Match", "*"), "{}").status(),
                    "* matches any version, so it is not a precondition");
            assertEquals(428, fn("POST", "api/v1/contracts/c1/" + action, Map.of("If-Match", " "), "{}").status());
        }
        assertEquals(calls, w.upstream.calls.size(), "a refused change never reaches an API host");
    }

    @Test
    void otherRoutesDoNotNeedIfMatch() {
        assertEquals(200, fn("POST", "api/v1/contracts", Map.of("Idempotency-Key", "key-12345"), "{}").status());
        assertEquals(200, fn("GET", "api/v1/contracts/c1", Map.of(), null).status());
        assertEquals(200, fn("GET", "api/v1/contracts/c1/installments", Map.of(), null).status());
        assertEquals(200, fn("POST", "api/v1/simulations", Map.of(), "{}").status());
        assertEquals(200, fn("PATCH", "api/v1/customers/abc/income", Map.of(), "{}").status());
    }
}
