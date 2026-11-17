package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.ports.Upstream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyRoutesTest {
    private final World w = new World();

    private GatewayResponse fn(String method, String path, String token, String body) {
        return w.handlers.proxy(HostKind.FUNCTIONS, World.req(method, path, token, "203.0.113.1", body));
    }

    private GatewayResponse aks(String method, String path, String token) {
        return w.handlers.proxy(HostKind.AKS, World.req(method, path, token, "203.0.113.1", null));
    }

    private static JsonNode trace(GatewayResponse r) throws Exception {
        return Json.MAPPER.readTree(Base64.getUrlDecoder().decode(r.headers().get("x-consignado-trace")));
    }

    private static List<String> stages(JsonNode trace) {
        List<String> out = new ArrayList<>();
        trace.get("events").forEach(e -> out.add(e.get("stage").asText()));
        return out;
    }

    @Test
    void guestMayCallOnlyTheStatelessPreview() {
        assertEquals(200, fn("POST", "api/v1/simulations/preview", null, "{\"principal\":1000}").status());
        assertEquals("guest", w.upstream.last().headers().get("X-Consignado-User"));
        int calls = w.upstream.calls.size();
        assertEquals(401, fn("GET", "api/v1/simulations/preview", null, null).status());
        assertEquals(401, fn("GET", "api/v1/customers", null, null).status());
        assertEquals(401, fn("POST", "api/v1/simulations", null, "{}").status());
        assertEquals(401, fn("POST", "api/v1/simulations/preview/x", null, "{}").status());
        assertEquals(401, aks("GET", "api/v1/customers", null).status());
        assertEquals(401, w.handlers.proxy(HostKind.AKS, World.req("POST", "api/v1/simulations/preview", null, null, "{}")).status());
        assertEquals(calls, w.upstream.calls.size(), "a refused call never reaches an API host");
    }

    @Test
    void onlyApiV1PathsAndThreeMethodsAreForwarded() {
        for (String path : new String[] {"actuator/health", "api/v2/customers", "api/v1/../actuator/env",
                "api/v1//customers", "api/v1/customers%2f..", "api/v1/a b", "", "api/v1/" + "x".repeat(600)}) {
            assertEquals(404, fn("GET", path, "good-u1", null).status(), path);
        }
        assertEquals(404, fn("GET", null, "good-u1", null).status());
        for (String method : new String[] {"PUT", "DELETE", "HEAD", "TRACE"}) {
            assertEquals(405, fn(method, "api/v1/customers", "good-u1", null).status(), method);
        }
        assertEquals(0, w.upstream.calls.size());
        assertEquals(200, fn("GET", "/api/v1/customers", "good-u1", null).status());
        assertEquals(200, fn("PATCH", "api/v1/customers/abc-123/income", "good-u1", "{\"monthlyIncome\":1}").status());
        assertEquals("api/v1/customers/abc-123/income", w.upstream.last().path());
    }

    @Test
    void bodyLimitIs64Kb() {
        assertEquals(200, fn("POST", "api/v1/simulations", "good-u1", "x".repeat(64 * 1024)).status());
        GatewayResponse r = fn("POST", "api/v1/simulations", "good-u1", "x".repeat(64 * 1024 + 1));
        assertEquals(413, r.status());
        assertEquals("body-too-large", World.json(r).get("code").asText());
    }

    @Test
    void queryIsForwardedWhenItIsClean() {
        Map<String, String> headers = Map.of("Authorization", "Bearer good-u1");
        w.handlers.proxy(HostKind.FUNCTIONS, GatewayRequest.of("GET", "api/v1/customers", "page=0&size=20", headers, null));
        assertEquals("page=0&size=20", w.upstream.last().query());
        GatewayResponse bad = w.handlers.proxy(HostKind.FUNCTIONS,
                GatewayRequest.of("GET", "api/v1/customers", "q=<script>", headers, null));
        assertEquals(400, bad.status());
    }

    @Test
    void authorizationCookiesAndHopByHopHeadersNeverGoUpstream() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer good-u1");
        headers.put("Cookie", "session=1");
        headers.put("Connection", "keep-alive");
        headers.put("Transfer-Encoding", "chunked");
        headers.put("Upgrade", "h2c");
        headers.put("Host", "consignado.kmads.dev");
        headers.put("X-Consignado-User", "someone-else");
        headers.put("x-functions-key", "client-sent");
        headers.put("X-Forwarded-For", "1.2.3.4");
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        w.handlers.proxy(HostKind.FUNCTIONS, GatewayRequest.of("POST", "api/v1/simulations", null, headers,
                "{}".getBytes(StandardCharsets.UTF_8)));
        Map<String, String> sent = new HashMap<>();
        w.upstream.last().headers().forEach((k, v) -> sent.put(k.toLowerCase(), v));
        assertEquals(java.util.Set.of("accept", "content-type", "x-consignado-user", "x-functions-key"), sent.keySet());
        assertEquals("u1", sent.get("x-consignado-user"));
        assertEquals("host-key-value", sent.get("x-functions-key"));
    }

    @Test
    void upstreamStatusAndBodyComeBackUnchangedWithTheTwoHeaders() throws Exception {
        w.upstream.status = 422;
        w.upstream.body = "{\"code\":\"margin-exceeded\",\"fullName\":\"Maria Example\"}".getBytes(StandardCharsets.UTF_8);
        GatewayResponse r = fn("POST", "api/v1/simulations", "good-u1", "{}");
        assertEquals(422, r.status());
        assertArrayEquals(w.upstream.body, r.body(), "the proxy does not change the JSON of the API");
        assertEquals("functions", r.headers().get("x-consignado-host"));
        assertEquals("x-consignado-host, x-consignado-trace", r.headers().get("Access-Control-Expose-Headers"));
        JsonNode t = trace(r);
        assertEquals("functions", t.get("host").asText());
        assertFalse(t.get("cold").asBoolean());
        assertEquals(List.of("request", "edge", "gateway", "identity", "keyvault", "database", "response"), stages(t));
    }

    @Test
    void traceHasMeasuredTimesAndMarksAColdCall() throws Exception {
        w.upstream.onCall = () -> w.nanos += 4_200_000_000L;
        GatewayResponse r = fn("POST", "api/v1/simulations/preview", null, "{}");
        JsonNode t = trace(r);
        assertTrue(t.get("cold").asBoolean());
        assertEquals(4200, t.get("totalMs").asLong());
        assertEquals(List.of("request", "edge", "gateway", "identity", "keyvault", "function-cold-start", "jvm-start",
                "response"), stages(t), "a guest never reaches the database stage");
        JsonNode cold = t.get("events").get(5);
        assertEquals(4200, cold.get("ms").asLong());
        assertEquals("done", cold.get("status").asText());
        assertFalse(t.get("events").get(6).has("ms"));
        assertEquals(0, t.get("events").get(3).get("ms").asLong());

        w.upstream.onCall = () -> w.nanos += 120_000_000L;
        t = trace(fn("GET", "api/v1/customers", "good-u1", null));
        assertFalse(t.get("cold").asBoolean());
        JsonNode keyvault = t.get("events").get(4);
        assertEquals("cached", keyvault.get("detail").asText());
        assertEquals(0, keyvault.get("ms").asLong());
        JsonNode response = t.get("events").get(t.get("events").size() - 1);
        assertEquals("response", response.get("stage").asText());
        assertEquals(120, response.get("ms").asLong());
        for (JsonNode e : t.get("events")) assertTrue(e.has("ts") && e.has("stage") && e.has("status"));
    }

    @Test
    void aksHostNeedsAnOwnOpenWindow() throws Exception {
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        GatewayResponse refused = aks("GET", "api/v1/customers", "good-u1");
        assertEquals(403, refused.status());
        assertEquals("no-session", World.json(refused).get("code").asText());

        w.createSession("u1");
        assertEquals(403, aks("GET", "api/v1/customers", "good-u1").status(), "a starting window is not open");
        w.makeReady();
        int before = w.upstream.calls.size();
        GatewayResponse ok = aks("GET", "api/v1/customers", "good-u1");
        assertEquals(200, ok.status());
        assertEquals("aks", ok.headers().get("x-consignado-host"));
        Upstream.Request sent = w.upstream.last();
        assertEquals(HostKind.AKS, sent.host());
        assertFalse(sent.headers().containsKey("x-functions-key"), "the function key goes only to the Functions host");
        assertEquals(before + 1, w.upstream.calls.size());
        JsonNode t = trace(ok);
        assertEquals("aks", t.get("host").asText());
        assertTrue(stages(t).containsAll(List.of("quota", "load-balancer", "response")));

        w.signUp("u2", "198.51.100.2", "fingerprint-u2");
        assertEquals(403, aks("GET", "api/v1/customers", "good-u2").status(), "the window of another user does not count");

        w.clock.plus(Duration.ofMinutes(15));
        assertEquals(403, aks("GET", "api/v1/customers", "good-u1").status(), "an expired window is closed before the timer runs");
    }

    @Test
    void blockedUserIsRefusedOnBothHosts() {
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        w.users.rows.get("u1").status = "blocked";
        assertEquals(403, fn("GET", "api/v1/customers", "good-u1", null).status());
        assertEquals(403, aks("GET", "api/v1/customers", "good-u1").status());
    }

    @Test
    void upstreamFailureGivesAGenericErrorWithATrace() throws Exception {
        w.upstream.fail = new Upstream.UpstreamException(false);
        GatewayResponse r = fn("GET", "api/v1/customers", "good-u1", null);
        assertEquals(502, r.status());
        assertEquals("upstream-unavailable", World.json(r).get("code").asText());
        JsonNode t = trace(r);
        assertEquals("error", t.get("events").get(t.get("events").size() - 1).get("status").asText());
        w.upstream.fail = new Upstream.UpstreamException(true);
        assertEquals(504, fn("GET", "api/v1/customers", "good-u1", null).status());
    }

    @Test
    void missingHostKeyGivesBadGatewayAndNoUpstreamCall() {
        w.vault.values.remove("apifn-host-key");
        GatewayResponse r = fn("GET", "api/v1/customers", "good-u1", null);
        assertEquals(502, r.status());
        assertEquals(0, w.upstream.calls.size());
    }
}
