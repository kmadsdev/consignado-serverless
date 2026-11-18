package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.ApiTiming;
import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.core.TraceBuilder;
import dev.kmads.consignado.gateway.core.Views;
import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.ports.Upstream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Contract addendum 2, part 2: the API's own timing (X-Consignado-Timing) in the request trace. */
class UpstreamTimingTest {
    private static final String WITH_DB = "{\"instance\":\"consignado-api-5d897fb475-ffmb4\",\"appMs\":41.3,"
            + "\"db\":{\"ms\":28.7,\"statements\":3,\"select\":2,\"insert\":1,\"update\":0,\"delete\":0,\"other\":0,"
            + "\"connectionMs\":1.2,\"transactions\":1}}";
    private static final String NO_DB = "{\"instance\":\"a1b2c3d4e5f6\",\"appMs\":8.6,\"db\":null}";

    private final World w = new World();

    private GatewayResponse fn(String method, String path, String token, String body) {
        return w.handlers.proxy(HostKind.FUNCTIONS, World.req(method, path, token, "203.0.113.1", body));
    }

    private GatewayResponse aks(String method, String path) {
        return w.handlers.proxy(HostKind.AKS, World.req(method, path, "good-u1", "203.0.113.1", "{}"));
    }

    private void openAksWindow() {
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        w.createSession("u1");
        w.makeReady();
    }

    private static JsonNode trace(GatewayResponse r) throws Exception {
        return Json.MAPPER.readTree(Base64.getUrlDecoder().decode(r.headers().get("x-consignado-trace")));
    }

    private static List<String> stages(JsonNode t) {
        List<String> out = new ArrayList<>();
        t.get("events").forEach(e -> out.add(e.get("stage").asText()));
        return out;
    }

    private static JsonNode stage(JsonNode t, String id) {
        for (JsonNode e : t.get("events")) if (e.get("stage").asText().equals(id)) return e;
        throw new AssertionError("no stage " + id);
    }

    private static long sum(JsonNode t) {
        long s = 0;
        for (JsonNode e : t.get("events")) if (e.has("ms")) s += e.get("ms").asLong();
        return s;
    }

    private static long sum(Views.RequestTrace t) {
        return t.events().stream().mapToLong(e -> e.ms() == null ? 0 : e.ms()).sum();
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void validHeaderIsParsed() {
        ApiTiming t = ApiTiming.parse(WITH_DB);
        assertNotNull(t);
        assertEquals("consignado-api-5d897fb475-ffmb4", t.instance());
        assertEquals(41.3, t.appMs());
        assertEquals(3, t.db().statements());
        assertTrue(t.touchedDatabase());
        assertEquals("3 statements: 2 select, 1 insert", t.databaseDetail());
        ApiTiming none = ApiTiming.parse(NO_DB);
        assertNotNull(none);
        assertNull(none.db());
        assertFalse(none.touchedDatabase());
        assertNotNull(ApiTiming.parse("{\"instance\":\"local\",\"appMs\":0}"), "a missing db is the same as null");
        assertEquals("1 statement: 1 update", ApiTiming.parse("{\"instance\":\"local\",\"appMs\":5,\"db\":{\"ms\":1,"
                + "\"statements\":1,\"select\":0,\"insert\":0,\"update\":1,\"delete\":0,\"other\":0,"
                + "\"connectionMs\":0,\"transactions\":1}}").databaseDetail());
    }

    @Test
    void doubtfulHeadersAreIgnored() {
        String db = "\"db\":{\"ms\":1,\"statements\":1,\"select\":1,\"insert\":0,\"update\":0,\"delete\":0,"
                + "\"other\":0,\"connectionMs\":0,\"transactions\":1}";
        for (String bad : new String[] {
                null, "", "   ", "not json", "[]", "\"x\"", "{}",
                "{\"instance\":\"a\",\"appMs\":1" + " ".repeat(ApiTiming.MAX_CHARS) + "}",
                "{\"instance\":\"" + "a".repeat(81) + "\",\"appMs\":1}",
                "{\"instance\":\"a b\",\"appMs\":1}",
                "{\"instance\":\"<script>\",\"appMs\":1}",
                "{\"instance\":\"pod\\u000a\",\"appMs\":1}",
                "{\"instance\":7,\"appMs\":1}",
                "{\"instance\":\"a\"}",
                "{\"instance\":\"a\",\"appMs\":\"41\"}",
                "{\"instance\":\"a\",\"appMs\":-1}",
                "{\"instance\":\"a\",\"appMs\":60001}",
                "{\"instance\":\"a\",\"appMs\":1e400}",
                "{\"instance\":\"a\",\"appMs\":1,\"db\":5}",
                "{\"instance\":\"a\",\"appMs\":1,\"db\":{}}",
                "{\"instance\":\"a\",\"appMs\":0.5," + db.replace("\"ms\":1", "\"ms\":9") + "}",
                "{\"instance\":\"a\",\"appMs\":30," + db.replace("\"statements\":1", "\"statements\":2") + "}",
                "{\"instance\":\"a\",\"appMs\":30," + db.replace("\"select\":1", "\"select\":1.5") + "}",
                "{\"instance\":\"a\",\"appMs\":30," + db.replace("\"select\":1", "\"select\":-1") + "}",
                "{\"instance\":\"a\",\"appMs\":30," + db.replace("\"transactions\":1", "\"transactions\":99999") + "}",
                "{\"instance\":\"a\",\"appMs\":30," + db.replace("\"ms\":1", "\"ms\":40") + "}",
        }) {
            assertNull(ApiTiming.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void unknownFieldsAreNeverPassedOn() throws Exception {
        ApiTiming t = ApiTiming.parse("{\"instance\":\"local\",\"appMs\":3,\"secret\":\"x\",\"db\":null}");
        assertNotNull(t);
        JsonNode out = Json.MAPPER.readTree(Json.bytes(t));
        assertFalse(out.has("secret"));
        assertTrue(out.has("db") && out.get("db").isNull(), "db stays null, as the API sent it");
    }

    // ------------------------------------------------------------------ stages

    @Test
    void functionsCallWithDatabase() throws Exception {
        w.upstream.timing = WITH_DB;
        w.upstream.status = 201;
        w.upstream.onCall = () -> w.nanos += 78_000_000L;
        JsonNode t = trace(fn("POST", "api/v1/customers", "good-u1", "{}"));
        assertEquals(List.of("request", "edge", "gateway", "identity", "keyvault", "functions-host", "app", "database",
                "response"), stages(t));
        assertEquals(78 - 41, stage(t, "functions-host").get("ms").asLong());
        assertEquals(41 - 29, stage(t, "app").get("ms").asLong());
        assertEquals("Spring Boot handled the request in consignado-api-5d897fb475-ffmb4",
                stage(t, "app").get("detail").asText());
        assertEquals(29, stage(t, "database").get("ms").asLong());
        assertEquals("3 statements: 2 select, 1 insert", stage(t, "database").get("detail").asText());
        assertEquals("HTTP 201 from the API host", stage(t, "response").get("detail").asText());
        assertFalse(stage(t, "response").has("ms"), "the upstream time is in the stages above");
        assertEquals(41.3, t.get("api").get("appMs").asDouble());
        assertEquals(3, t.get("api").get("db").get("statements").asInt());
        assertEquals(t.get("totalMs").asLong(), sum(t));
    }

    @Test
    void guestPreviewHasNoIdentityAndNoDatabase() throws Exception {
        w.upstream.timing = NO_DB;
        w.upstream.onCall = () -> w.nanos += 50_000_000L;
        JsonNode t = trace(fn("POST", "api/v1/simulations/preview", null, "{}"));
        assertEquals(List.of("request", "edge", "gateway", "keyvault", "functions-host", "app", "response"), stages(t));
        assertEquals(41, stage(t, "functions-host").get("ms").asLong());
        assertEquals(9, stage(t, "app").get("ms").asLong());
        assertTrue(t.get("api").get("db").isNull());
        assertEquals(t.get("totalMs").asLong(), sum(t));
    }

    @Test
    void coldFunctionsCallKeepsTheColdBlockAndTheHostIsTheRest() throws Exception {
        w.upstream.timing = NO_DB.replace("8.6", "412.4");
        w.upstream.onCall = () -> w.nanos += 11_800_000_000L;
        JsonNode t = trace(fn("POST", "api/v1/simulations/preview", null, "{}"));
        assertTrue(t.get("cold").asBoolean());
        assertEquals(List.of("request", "edge", "gateway", "keyvault", "function-cold-start", "jvm-start",
                "functions-host", "app", "response"), stages(t));
        assertEquals(11800 - 412, stage(t, "function-cold-start").get("ms").asLong());
        assertFalse(stage(t, "jvm-start").has("ms"));
        assertFalse(stage(t, "functions-host").has("ms"));
        assertEquals(412, stage(t, "app").get("ms").asLong());
        assertEquals(t.get("totalMs").asLong(), sum(t));
    }

    @Test
    void aksCallWithDatabase() throws Exception {
        openAksWindow();
        w.upstream.timing = WITH_DB;
        w.upstream.onCall = () -> w.nanos += 47_000_000L;
        JsonNode t = trace(aks("POST", "api/v1/customers"));
        assertEquals(List.of("request", "edge", "gateway", "identity", "quota", "keyvault", "load-balancer", "app",
                "database", "response"), stages(t));
        assertEquals(47 - 41, stage(t, "load-balancer").get("ms").asLong());
        assertEquals("done", stage(t, "load-balancer").get("status").asText());
        assertEquals(12, stage(t, "app").get("ms").asLong());
        assertEquals(29, stage(t, "database").get("ms").asLong());
        assertEquals("consignado-api-5d897fb475-ffmb4", t.get("api").get("instance").asText());
        assertEquals(t.get("totalMs").asLong(), sum(t));
    }

    @Test
    void noOrBadHeaderGivesTheTraceAsBefore() throws Exception {
        for (String header : new String[] {null, "{\"instance\":\"a b\",\"appMs\":1}"}) {
            w.upstream.timing = header;
            w.upstream.onCall = () -> w.nanos += 30_000_000L;
            JsonNode t = trace(fn("GET", "api/v1/customers", "good-u1", null));
            assertEquals(List.of("request", "edge", "gateway", "identity", "keyvault", "response"), stages(t));
            assertEquals(30, stage(t, "response").get("ms").asLong());
            assertFalse(t.has("api"));
            assertEquals(t.get("totalMs").asLong(), sum(t));
        }
    }

    @Test
    void appTimeLongerThanTheMeasuredCallIsADoubt() throws Exception {
        w.upstream.timing = NO_DB.replace("8.6", "500");
        w.upstream.onCall = () -> w.nanos += 30_000_000L;
        JsonNode t = trace(fn("GET", "api/v1/customers", "good-u1", null));
        assertFalse(t.has("api"));
        assertFalse(stages(t).contains("app"));
        // Within 2 ms of rounding the header is used, and the host stage is 0, never below.
        w.upstream.timing = NO_DB.replace("8.6", "31.4");
        t = trace(fn("GET", "api/v1/customers", "good-u1", null));
        assertEquals(0, stage(t, "functions-host").get("ms").asLong());
        assertEquals(30, stage(t, "app").get("ms").asLong());
        assertEquals(t.get("totalMs").asLong(), sum(t));
    }

    // ------------------------------------------------------------------ failures

    @Test
    void upstreamErrorFailsTheHostStageWithNoAppOrDatabase() throws Exception {
        w.upstream.timing = WITH_DB;
        w.upstream.status = 500;
        w.upstream.onCall = () -> w.nanos += 60_000_000L;
        JsonNode t = trace(fn("POST", "api/v1/customers", "good-u1", "{}"));
        JsonNode host = stage(t, "functions-host");
        assertEquals("error", host.get("status").asText());
        assertEquals("upstream answered 500", host.get("detail").asText());
        assertEquals(60, host.get("ms").asLong());
        assertFalse(stages(t).contains("app") || stages(t).contains("database"));
        assertFalse(t.has("api"));
        assertEquals("error", stage(t, "response").get("status").asText());
        assertEquals(t.get("totalMs").asLong(), sum(t));

        w.upstream.fail = new Upstream.UpstreamException(true);
        t = trace(fn("POST", "api/v1/customers", "good-u1", "{}"));
        assertEquals("timeout after 30 s", stage(t, "functions-host").get("detail").asText());
        assertEquals("error", stage(t, "functions-host").get("status").asText());
        assertFalse(stages(t).contains("app"));
        assertEquals(t.get("totalMs").asLong(), sum(t));
    }

    @Test
    void aksTimeoutFailsTheLoadBalancerStage() throws Exception {
        openAksWindow();
        w.upstream.timing = WITH_DB;
        w.upstream.fail = new Upstream.UpstreamException(true);
        w.upstream.onCall = () -> w.nanos += 10_000_000_000L;
        JsonNode t = trace(aks("GET", "api/v1/customers"));
        JsonNode lb = stage(t, "load-balancer");
        assertEquals("error", lb.get("status").asText());
        assertEquals("timeout after 10 s", lb.get("detail").asText());
        assertEquals(10_000, lb.get("ms").asLong());
        assertFalse(stages(t).contains("app") || stages(t).contains("database"));
        assertEquals(t.get("totalMs").asLong(), sum(t));
    }

    // ------------------------------------------------------------------ headers and invariant

    @Test
    void upstreamTimingHeadersAreNotPassedToTheBrowser() throws Exception {
        w.upstream.timing = WITH_DB;
        w.upstream.onCall = () -> w.nanos += 78_000_000L;
        GatewayResponse r = fn("POST", "api/v1/customers", "good-u1", "{}");
        for (String name : r.headers().keySet()) {
            String n = name.toLowerCase(Locale.ROOT);
            assertNotEquals("x-consignado-timing", n);
            assertNotEquals("server-timing", n);
        }
        assertEquals("x-consignado-host, x-consignado-trace, etag, location", r.headers().get("Access-Control-Expose-Headers"));
        assertTrue(trace(r).has("api"), "the data reaches the browser only inside the trace");
    }

    @Test
    void stageTimesAlwaysAddUpToTheTotal() {
        Instant now = Instant.parse("2026-11-17T22:50:00Z");
        ApiTiming withDb = ApiTiming.parse(WITH_DB);
        ApiTiming noDb = ApiTiming.parse(NO_DB);
        ApiTiming[] timings = {null, withDb, noDb, ApiTiming.parse(NO_DB.replace("8.6", "0"))};
        long[] upstreams = {-1, 0, 7, 40, 41, 43, 120, 8000, 8001, 11800, 30000};
        int[] statuses = {0, 200, 201, 404, 500, 503};
        String[] failures = {null, "timeout after 30 s", "function key not found in Key Vault"};
        int checked = 0;
        for (HostKind host : HostKind.values()) {
            for (boolean guest : new boolean[] {true, false}) {
                for (ApiTiming timing : timings) {
                    for (long up : upstreams) {
                        for (int status : statuses) {
                            for (String failure : failures) {
                                long identity = guest ? 0 : 4;
                                long kv = host == HostKind.FUNCTIONS ? 3 : 0;
                                long total = Math.max(up, 0) + identity + kv + 13;
                                Views.RequestTrace t = TraceBuilder.forProxy(host, now, guest, identity, kv, false,
                                        host == HostKind.FUNCTIONS, up, up < 0 ? 0 : status, total, failure, timing);
                                assertEquals(total, sum(t), host + " guest=" + guest + " up=" + up + " status="
                                        + status + " timing=" + timing + " failure=" + failure);
                                if (!guest) assertTrue(t.events().stream().anyMatch(e -> e.stage().equals("identity")));
                                else assertTrue(t.events().stream().noneMatch(e -> e.stage().equals("identity")));
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 1000);
    }
}
