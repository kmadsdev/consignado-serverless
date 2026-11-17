package dev.kmads.consignado.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.ClusterView;
import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.fakes.World;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Defects 13, 14 and 16 of the second live test (reports/e2e.md). */
class LiveDefectsTest {
    private final World w = new World();

    private static JsonNode stage(JsonNode session, String id) {
        for (JsonNode e : session.get("events")) {
            if (e.get("stage").asText().equals(id) && e.get("status").asText().equals("done")) return e;
        }
        throw new AssertionError("no done stage " + id + " in " + session);
    }

    /** A pod made after the request, with its own times (seconds after t0). */
    private ClusterView podView(Instant created, Instant t0) {
        ClusterView.PodTimes times = new ClusterView.PodTimes(t0.plusSeconds(1), t0.plusSeconds(1),
                t0.plusSeconds(4), t0.plusSeconds(4), t0.plusSeconds(60), t0.plusSeconds(61), t0.plusSeconds(35));
        ClusterView.Container c = new ClusterView.Container("consignado-api", "running", null, true, 0,
                "crconsignadokm01.azurecr.io/consignado-api:abc123");
        ClusterView.Pod pod = new ClusterView.Pod("consignado-api-7d9f-abcde", "aks-nodepool1-123-vmss000000",
                "Running", true, true, true, true, true, List.of(c), created, times);
        return new ClusterView(true, List.of(new ClusterView.Node("aks-nodepool1-123-vmss000000", true)),
                List.of(pod), 1);
    }

    private JsonNode coldStart(Optional<ClusterView.ImagePull> pull, boolean freshPod) {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        String id = World.json(w.createSession("u1")).get("id").asText();
        Instant requested = w.clock.now;
        w.clock.plus(Duration.ofMinutes(7));
        Instant t0 = requested.plus(Duration.ofMinutes(6));
        w.cluster.running();
        w.cluster.view = podView(freshPod ? t0 : requested.minus(Duration.ofHours(3)), t0);
        w.cluster.imagePull = pull;
        w.upstream.readiness = 200;
        w.engine.tick();
        return World.json(w.handlers.sessionsGet(World.req("GET", id, "good-u1", null, null)));
    }

    // ------------------------------------------------------------- defect 16

    @Test
    void podStagesGetRealDurationsFromThePodTimes() {
        Instant base = w.clock.now.plus(Duration.ofMinutes(6));
        JsonNode s = coldStart(Optional.of(new ClusterView.ImagePull(false, base.plusSeconds(5), base.plusSeconds(30))),
                true);
        assertEquals("ready", s.get("state").asText());
        assertEquals(3000, stage(s, "secrets-mount").get("ms").asLong(), "PodScheduled -> PodReadyToStartContainers");
        assertEquals(25000, stage(s, "image-pull").get("ms").asLong(), "Pulling -> Pulled");
        assertEquals("image pulled for pod consignado-api-7d9f-abcde", stage(s, "image-pull").get("detail").asText());
        assertEquals(5000, stage(s, "container-start").get("ms").asLong(), "Pulled -> running.startedAt");
        assertEquals(26000, stage(s, "database").get("ms").asLong(), "startedAt -> Ready");
        assertEquals(1, w.cluster.imagePullReads);
    }

    @Test
    void imageAlreadyOnTheNodeIsZeroWithThatText() {
        JsonNode s = coldStart(Optional.of(new ClusterView.ImagePull(true, null, null)), true);
        assertEquals(0, stage(s, "image-pull").get("ms").asLong());
        assertEquals("image already on the node", stage(s, "image-pull").get("detail").asText());
        assertEquals(31000, stage(s, "container-start").get("ms").asLong(), "from volumes ready (+4 s) to +35 s");
    }

    @Test
    void anOldPodGivesNoPodDurationsAndNoEventRead() {
        JsonNode s = coldStart(Optional.empty(), false);
        assertEquals(0, stage(s, "container-start").get("ms").asLong());
        assertEquals("image present for pod consignado-api-7d9f-abcde", stage(s, "image-pull").get("detail").asText());
        assertEquals(0, w.cluster.imagePullReads);
    }

    @Test
    void eventsNotReadableSaysSo() {
        JsonNode s = coldStart(Optional.empty(), true);
        assertEquals(0, stage(s, "image-pull").get("ms").asLong());
        assertTrue(stage(s, "image-pull").get("detail").asText().endsWith("(pull time not readable)"));
        assertEquals(26000, stage(s, "database").get("ms").asLong());
    }

    // ------------------------------------------------------------- defect 13

    @Test
    void answeredAksCallsAreCountedPerSession() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        String id = World.json(w.createSession("u1")).get("id").asText();
        w.makeReady();
        for (int i = 0; i < 3; i++) {
            assertEquals(200, w.handlers.proxy(HostKind.AKS,
                    World.req("GET", "api/v1/customers", "good-u1", null, null)).status());
        }
        w.upstream.status = 422;
        w.handlers.proxy(HostKind.AKS, World.req("POST", "api/v1/customers", "good-u1", null, "{}"));
        w.upstream.status = 200;
        w.handlers.proxy(HostKind.FUNCTIONS, World.req("GET", "api/v1/customers", "good-u1", null, null));
        assertEquals(3, w.sessions.find(id).orElseThrow().requests);
        JsonNode list = World.json(w.handlers.admin(World.req("GET", "sessions", "good-" + World.ADMIN, null, null)));
        assertEquals(3, list.get(0).get("requests").asInt());
        assertEquals(3, World.json(w.handlers.sessionsGet(World.req("GET", id, "good-u1", null, null)))
                .get("requests").asInt());
    }

    @Test
    void aConflictingSessionWriteIsRetriedOnTheNewRow() {
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        String id = World.json(w.createSession("u1")).get("id").asText();
        w.makeReady();
        // The timer writes the row while the call is upstream.
        w.upstream.onCall = () -> {
            var s = w.sessions.find(id).orElseThrow();
            s.lastObservedAt = w.clock.now;
            w.sessions.save(s);
            w.upstream.onCall = () -> { };
        };
        assertEquals(200, w.handlers.proxy(HostKind.AKS,
                World.req("GET", "api/v1/customers", "good-u1", null, null)).status());
        assertEquals(1, w.sessions.find(id).orElseThrow().requests);
    }

    // ------------------------------------------------------------- defect 14

    @Test
    void engineSaysPausedAlsoWhileAWindowRuns() {
        JsonNode e = World.json(w.handlers.engine(World.req("GET", null, null, null, null)));
        assertFalse(e.get("paused").asBoolean());
        w.signUp("u1", "203.0.113.1", "fp-aaaaaaaaaaaa");
        w.createSession("u1");
        w.makeReady();
        w.handlers.admin(World.req("POST", "pause", "good-" + World.ADMIN, null, "{\"paused\":true}"));
        e = World.json(w.handlers.engine(World.req("GET", null, null, null, null)));
        assertEquals("running", e.get("state").asText());
        assertTrue(e.get("paused").asBoolean());
        assertEquals("admin", e.get("pausedReason").asText());
        w.clock.plus(Duration.ofMinutes(15));
        w.engine.tick();
        w.cluster.stopped();
        w.engine.tick();
        w.clock.plus(Duration.ofMinutes(16));
        e = World.json(w.handlers.engine(World.req("GET", null, null, null, null)));
        assertEquals("paused", e.get("state").asText());
        assertTrue(e.get("paused").asBoolean());
    }
}
