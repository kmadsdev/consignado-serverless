package dev.kmads.consignado.gateway.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.Config;
import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.http.GatewayResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmallRulesTest {

    @Test
    void namesAndCpfAreMasked() {
        assertEquals("M**** d* S****", Masker.name("Maria da Silva"));
        assertEquals("J*", Masker.name("J"));
        assertEquals("", Masker.name(null));
        assertEquals("***.456.***-**", Masker.cpf("123.456.789-09"));
        assertEquals("***.456.***-**", Masker.cpf("12345678909"));
        assertEquals("***.***.***-**", Masker.cpf("***.***.***-04"));
        assertEquals("***.***.***-**", Masker.cpf("123"));
    }

    @Test
    void maskingReachesNestedObjectsAndDropsContactFields() throws Exception {
        JsonNode tree = Json.MAPPER.readTree("[{\"id\":\"1\",\"fullName\":\"Maria Example\",\"cpf\":\"12345678909\","
                + "\"netMonthlySalary\":4200.0,\"email\":\"m@example.com\",\"customer\":{\"name\":\"Joao Lima\",\"cpf\":\"98765432100\"}}]");
        String out = Json.string(Masker.maskPersons(tree));
        assertFalse(out.contains("Maria") || out.contains("Example") || out.contains("Joao") || out.contains("Lima"));
        assertFalse(out.contains("12345678909") || out.contains("98765432100") || out.contains("m@example.com"));
        assertTrue(out.contains("\"fullName\":\"M**** E****\""));
        assertTrue(out.contains("\"netMonthlySalary\":4200.0"));
    }

    @Test
    void snapshotIsEmptyAtFirstThenMaskedAndRefreshedEveryTenMinutes() throws Exception {
        World w = new World();
        JsonNode empty = World.json(w.handlers.snapshot(World.req("GET", null, null, null, null)));
        assertTrue(empty.has("asOf"));
        assertEquals(0, empty.get("customers").size());

        w.upstream.bodies.put("api/v1/agreements", "[{\"id\":\"a1\",\"name\":\"INSS\"}]");
        w.upstream.bodies.put("api/v1/customers", "{\"content\":[{\"id\":\"c1\",\"cpf\":\"***.***.***-04\",\"fullName\":\"Maria Example\",\"netMonthlySalary\":4200.0}],\"page\":0}");
        w.upstream.bodies.put("api/v1/contracts", "{\"content\":[{\"id\":\"k1\",\"customerId\":\"c1\",\"status\":\"ACTIVE\"}]}");
        w.handlers.reaper();
        JsonNode snap = Json.MAPPER.readTree(w.blob.get("snapshot"));
        assertEquals("INSS", snap.get("agreements").get(0).get("name").asText(), "an agreement name is not personal data");
        assertEquals("M**** E****", snap.get("customers").get(0).get("fullName").asText());
        assertEquals("***.***.***-**", snap.get("customers").get(0).get("cpf").asText());
        assertEquals("k1", snap.get("contracts").get(0).get("id").asText());
        assertFalse(w.blob.get("snapshot").contains("Maria"));
        assertEquals("gateway-snapshot", w.upstream.last().headers().get("X-Consignado-User"));
        assertEquals("host-key-value", w.upstream.last().headers().get("x-functions-key"));

        int calls = w.upstream.calls.size();
        w.clock.plus(Duration.ofMinutes(9));
        w.handlers.reaper();
        assertEquals(calls, w.upstream.calls.size());
        w.clock.plus(Duration.ofMinutes(1));
        w.handlers.reaper();
        assertEquals(calls + 3, w.upstream.calls.size());

        w.clock.plus(Duration.ofMinutes(2));
        GatewayResponse r = w.handlers.snapshot(World.req("GET", null, null, null, null));
        assertEquals("M**** E****", World.json(r).get("customers").get(0).get("fullName").asText());
    }

    @Test
    void failedSnapshotSourceKeepsTheOldBlob() {
        World w = new World();
        w.blob.put("snapshot", "{\"asOf\":\"old\"}");
        w.upstream.status = 500;
        assertFalse(w.snapshot.refresh());
        assertEquals("{\"asOf\":\"old\"}", w.blob.get("snapshot"));
    }

    @Test
    void traceHeaderIsBase64UrlJson() throws Exception {
        Views.RequestTrace trace = TraceBuilder.forProxy(HostKind.FUNCTIONS, java.time.Instant.parse("2026-11-17T12:00:00Z"),
                false, 12, 30, false, true, 250, 200, 300);
        String header = TraceBuilder.encode(trace);
        assertTrue(header.matches("[A-Za-z0-9_-]+"));
        JsonNode t = Json.MAPPER.readTree(java.util.Base64.getUrlDecoder().decode(header));
        assertEquals(300, t.get("totalMs").asLong());
        assertEquals(8, t.get("events").get(2).get("ms").asLong(), "gateway = total - identity - keyvault - upstream");
        assertEquals(12, t.get("events").get(3).get("ms").asLong());
        assertEquals("read from Key Vault", t.get("events").get(4).get("detail").asText());
        assertEquals("2026-11-17T12:00:00Z", t.get("events").get(0).get("ts").asText());
        for (JsonNode e : t.get("events")) assertTrue(Stages.FUNCTIONS.contains(e.get("stage").asText()));
    }

    @Test
    void stageIdsEqualTheContract() {
        assertEquals(19, Stages.AKS.size());
        assertEquals("request", Stages.AKS.get(0));
        assertEquals("cluster-stop", Stages.AKS.get(18));
        assertEquals(java.util.List.of("request", "edge", "gateway", "identity", "keyvault", "function-cold-start",
                "jvm-start", "functions-host", "app", "database", "response"), Stages.FUNCTIONS);
        assertEquals(java.util.List.of("request", "edge", "gateway", "identity", "quota", "keyvault", "load-balancer",
                "app", "database", "response"), Stages.AKS_REQUEST);
    }

    @Test
    void validationRules() {
        assertEquals("https://www.linkedin.com/in/ana-souza/", Validate.linkedin("https://www.linkedin.com/in/ana-souza/"));
        assertEquals("https://br.linkedin.com/in/ana", Validate.linkedin("https://br.linkedin.com/in/ana"));
        for (String bad : new String[] {"https://www.linkedin.com.evil.io/in/ana", "https://linkedin.com/company/x",
                "javascript:alert(1)//linkedin.com/in/ana", "https://www.linkedin.com/in/ana?x=1"}) {
            assertThrows(ApiException.class, () -> Validate.linkedin(bad), bad);
        }
        assertEquals("a.b+c@example.com.br", Validate.email("a.b+c@example.com.br"));
        for (String bad : new String[] {"a@b", "a b@example.com", "a@example..com", "@example.com", "a@example.com\nBcc: x@y.z"}) {
            assertThrows(ApiException.class, () -> Validate.email(bad), bad);
        }
        assertThrows(ApiException.class, () -> Validate.singleLine("name", "Ana\nSouza", 2, 80));
        assertThrows(ApiException.class, () -> Validate.text("message", "a\u0000b", 1, 10));
        assertTrue(Validate.disposableEmail("x@Mailinator.com"));
        assertFalse(Validate.disposableEmail("x@example.com"));
    }

    @Test
    void rateGuardCountsPerKeyInAPeriod() {
        World.TestClock clock = new World.TestClock();
        RateGuard guard = new RateGuard(2, Duration.ofMinutes(1), clock);
        guard.check("a");
        guard.check("a");
        ApiException e = assertThrows(ApiException.class, () -> guard.check("a"));
        assertEquals(429, e.status());
        assertTrue(e.retryAfterSeconds() > 0);
        guard.check("b");
        clock.plus(Duration.ofSeconds(61));
        guard.check("a");
    }

    @Test
    void hashIsStableKeyedAndNeverTheRawValue() {
        World.Vault vault = new World.Vault();
        vault.values.put("hash-key", "key-one");
        Hasher hasher = new Hasher(vault);
        String h = hasher.hash("203.0.113.1");
        assertEquals(h, hasher.hash("203.0.113.1"));
        assertNotEquals(h, hasher.hash("203.0.113.2"));
        World.Vault other = new World.Vault();
        other.values.put("hash-key", "key-two");
        assertNotEquals(h, new Hasher(other).hash("203.0.113.1"), "another key gives another hash");
        assertThrows(IllegalStateException.class, () -> new Hasher(new World.Vault()).hash("203.0.113.1"));
        assertEquals(h, new Hasher(vault).hash("203.0.113.1"));
        assertNull(hasher.hash(null));
    }

    private static Map<String, String> env() {
        Map<String, String> e = new HashMap<>();
        e.put("AZURE_CLIENT_ID", "c");
        e.put("ENTRA_TENANT_ID", "t");
        e.put("ENTRA_API_AUDIENCE", "a");
        e.put("ADMIN_OID", "o");
        e.put("AKS_RESOURCE_ID", "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-consignado/providers/Microsoft.ContainerService/managedClusters/aks-consignado");
        e.put("AKS_API_SERVER_URL", "https://aks.example:443/");
        e.put("AKS_CA_CERT_B64", "x");
        e.put("AKS_INTERNAL_URL", "http://10.10.3.200");
        e.put("APIFN_BASE_URL", "https://fn.example");
        e.put("KEY_VAULT_URL", "https://kv.example");
        e.put("STORAGE_ACCOUNT", "stconsigappkm01");
        e.put("OWNER_EMAIL", "owner@example.com");
        e.put("WINDOW_MINUTES", "15");
        e.put("WINDOWS_MAX", "3");
        e.put("COOLDOWN_MINUTES", "15");
        e.put("BUDGET_LIMIT_USD", "60");
        return e;
    }

    @Test
    void configReadsEveryVariableAndFailsWithTheNameOnly() {
        Map<String, String> e = env();
        Config c = Config.load(e::get);
        assertEquals("https://aks.example:443", c.aksApiServerUrl());
        assertEquals(Duration.ofMinutes(15), c.settings().window());
        assertEquals(Duration.ofMinutes(20), c.settings().idleGrace());
        assertFalse(c.allowDevOrigin());
        // Fail closed (L2): no EDGE, "cloudflare" or a typo keep the edge key on; only EDGE=local turns it off.
        assertTrue(c.edgeKeyRequired());
        for (String v : new String[] {"cloudflare", "Cloudflare", "none", "", "lokal"}) {
            Map<String, String> cf = env();
            cf.put("EDGE", v);
            assertTrue(Config.load(cf::get).edgeKeyRequired(), v);
        }
        Map<String, String> local = env();
        local.put("EDGE", "local");
        assertFalse(Config.load(local::get).edgeKeyRequired());
        assertEquals(12, c.settings().dailyWindowsMax());
        Map<String, String> daily = env();
        daily.put("DAILY_WINDOWS_MAX", "5");
        assertEquals(5, Config.load(daily::get).settings().dailyWindowsMax());
        daily.put("DAILY_WINDOWS_MAX", "0");
        assertThrows(IllegalStateException.class, () -> Config.load(daily::get));
        assertNull(c.spaClientId());
        assertEquals(java.util.List.of("consignado.kmads.dev", "cluster.consignado.kmads.dev"), c.turnstileHostnames());
        assertNull(c.acsEndpoint());
        assertFalse(c.toString().contains("owner@example.com"));

        for (String name : env().keySet()) {
            Map<String, String> broken = env();
            broken.remove(name);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Config.load(broken::get));
            assertEquals("Missing environment variable: " + name, ex.getMessage());
        }
        e.put("WINDOW_MINUTES", "secret-looking-value");
        assertEquals("Invalid environment variable: WINDOW_MINUTES",
                assertThrows(IllegalStateException.class, () -> Config.load(e::get)).getMessage());
        Map<String, String> http = env();
        http.put("KEY_VAULT_URL", "http://kv.example");
        assertThrows(IllegalStateException.class, () -> Config.load(http::get));
        Map<String, String> acs = env();
        acs.put("ACS_ENDPOINT", "https://acs.example");
        assertNull(Config.load(acs::get).acsSender(), "no sender: the mailer writes audit rows only");
        acs.put("EMAIL_SENDER", "DoNotReply@example.com");
        assertEquals("DoNotReply@example.com", Config.load(acs::get).acsSender());
        assertEquals("request failed: java.lang.IllegalStateException: Missing environment variable: X",
                Logs.fmt("request failed: {0}", Logs.of(new IllegalStateException("Missing environment variable: X"))));
    }
}
