package dev.kmads.consignado.gateway.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.http.GatewayResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineServiceTest {
    private final World w = new World();

    private Session session(String id) {
        return w.sessions.find(id).orElseThrow();
    }

    private String start(String oid) {
        w.signUp(oid, "203.0.113." + (oid.hashCode() & 0x7f), "fingerprint-" + oid);
        GatewayResponse r = w.createSession(oid);
        assertEquals(201, r.status(), new String(r.body()));
        return World.json(r).get("id").asText();
    }

    private List<String> doneStages(Session s) {
        return s.events.stream().filter(e -> e.status().equals("done")).map(LifecycleEvent::stage).toList();
    }

    @Test
    void newWindowStartsTheStoppedClusterAndRecordsTheFirstStages() {
        String id = start("u1");
        Session s = session(id);
        assertEquals("starting", s.state);
        assertEquals(List.of("request", "edge", "gateway", "identity", "quota", "keyvault", "arm-start"), doneStages(s));
        assertEquals(1, w.cluster.starts);
        assertEquals("starting", w.engine.view().state());
        assertEquals(1, w.engine.view().activeSessions());
        assertTrue(w.audit.contains("engine.start"));
        assertTrue(w.audit.contains("session.create"));
        assertEquals(0, w.users.rows.get("u1").windowsUsed, "a window is counted only when it opens");
    }

    @Test
    void stagesFollowTheRealSignalsInOrderUntilTheWindowOpens() {
        String id = start("u1");
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        assertEquals("active", session(id).last("control-plane").status());

        w.cluster.running();
        ClusterView.Container waiting = new ClusterView.Container("consignado-api", "waiting", "ContainerCreating", false);
        ClusterView.Pod pod = new ClusterView.Pod("consignado-api-1", "node-a", "Pending", true, true, false, false, false,
                List.of(waiting));
        w.cluster.view = new ClusterView(true, List.of(new ClusterView.Node("node-a", true)), List.of(pod), 0);
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        Session s = session(id);
        assertTrue(s.done("control-plane"));
        assertTrue(s.done("node"));
        assertEquals("node node-a is Ready", s.last("node").detail());
        assertEquals("active", s.last("image-pull").status());
        assertTrue(s.last("image-pull").detail().contains("ContainerCreating"));
        assertFalse(s.done("container-start"));

        w.cluster.view = World.readyView();
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        s = session(id);
        assertTrue(s.done("readiness"));
        assertEquals("starting", s.state, "pods Ready is not enough: the real call must answer");
        assertEquals("active", s.last("load-balancer").status());

        w.upstream.readiness = 200;
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        s = session(id);
        assertEquals("ready", s.state);
        assertEquals(Stages.AKS.subList(0, 17), doneStages(s));
        assertEquals(w.clock.now, s.readyAt);
        assertEquals(w.clock.now.plus(Duration.ofMinutes(15)), s.expiresAt);
        assertEquals(60000L, s.last("load-balancer").ms());
        assertEquals("running", w.engine.view().state());
        assertEquals(1, w.users.rows.get("u1").windowsUsed);
    }

    @Test
    void imagePullErrorIsRecordedAsAnErrorEvent() {
        String id = start("u1");
        w.cluster.running();
        ClusterView.Container c = new ClusterView.Container("consignado-api", "waiting", "ImagePullBackOff", false);
        ClusterView.Pod pod = new ClusterView.Pod("consignado-api-1", "node-a", "Pending", true, true, false, false, false, List.of(c));
        w.cluster.view = new ClusterView(true, List.of(new ClusterView.Node("node-a", true)), List.of(pod), 0);
        w.engine.tick();
        assertEquals("error", session(id).last("image-pull").status());
    }

    @Test
    void pollAdvancesTheSessionButNotMoreOftenThanEveryThreeSeconds() {
        String id = start("u1");
        w.cluster.running();
        w.cluster.view = World.readyView();
        w.upstream.readiness = 200;
        GatewayResponse r = w.handlers.sessionsGet(World.req("GET", id, "good-u1", null, null));
        assertEquals("ready", World.json(r).get("state").asText());
        assertEquals("running", w.engine.view().state());
        int calls = w.upstream.calls.size();
        w.handlers.sessionsGet(World.req("GET", id, "good-u1", null, null));
        assertEquals(calls, w.upstream.calls.size());
    }

    @Test
    void sessionIsVisibleOnlyToItsOwnerAndTheAdmin() {
        String id = start("u1");
        w.signUp("u2", "198.51.100.2", "fingerprint-u2");
        assertEquals(404, w.handlers.sessionsGet(World.req("GET", id, "good-u2", null, null)).status());
        assertEquals(200, w.handlers.sessionsGet(World.req("GET", id, "good-" + World.ADMIN, null, null)).status());
        assertEquals(401, w.handlers.sessionsGet(World.req("GET", id, null, null, null)).status());
        assertEquals(404, w.handlers.sessionsGet(World.req("GET", "../x", "good-u1", null, null)).status());
    }

    @Test
    void windowExpiresThenClusterStopsThenCooldownThenStopped() {
        String id = start("u1");
        w.makeReady();
        w.clock.plus(Duration.ofMinutes(14));
        w.engine.tick();
        assertEquals("ready", session(id).state);
        assertEquals(0, w.cluster.stops);

        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        Session s = session(id);
        assertEquals("ended", s.state);
        assertTrue(s.done("window-close"));
        assertEquals(1, w.cluster.stops);
        assertEquals("stopping", w.engine.view().state());
        assertEquals("active", s.last("cluster-stop").status());
        assertNull(w.users.rows.get("u1").activeSessionId);
        assertTrue(w.audit.contains("engine.stop"));

        w.cluster.stopped();
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        s = session(id);
        assertEquals("done", s.last("cluster-stop").status());
        assertFalse(s.open);
        assertEquals("cooldown", w.engine.view().state());

        GatewayResponse refused = w.createSession("u1");
        assertEquals(409, refused.status());
        assertEquals("cooldown", World.json(refused).get("code").asText());
        assertNotNull(refused.headers().get("Retry-After"));

        w.clock.plus(Duration.ofMinutes(15));
        w.engine.tick();
        assertEquals("stopped", w.engine.view().state());
        assertEquals(201, w.createSession("u1").status());
        assertEquals(2, w.cluster.starts);
    }

    @Test
    void secondRequestReturnsTheSameActiveWindow() {
        String id = start("u1");
        GatewayResponse again = w.createSession("u1");
        assertEquals(200, again.status());
        assertEquals(id, World.json(again).get("id").asText());
        assertEquals(1, w.cluster.starts);
    }

    @Test
    void quotaOfThreeWindows() {
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        w.users.rows.get("u1").windowsUsed = 3;
        GatewayResponse r = w.createSession("u1");
        assertEquals(409, r.status());
        assertEquals("no-windows", World.json(r).get("code").asText());
        assertEquals(0, w.cluster.starts);
    }

    @Test
    void heldBlockedAndIncompleteProfilesAreRefused() {
        w.handlers.me(World.req("GET", null, "good-u1", "203.0.113.1", null));
        assertEquals("profile-incomplete", World.json(w.createSession("u1")).get("code").asText());
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        w.users.rows.get("u1").status = "held";
        assertEquals("held", World.json(w.createSession("u1")).get("code").asText());
        w.users.rows.get("u1").status = "blocked";
        assertEquals("blocked", World.json(w.createSession("u1")).get("code").asText());
        assertEquals(0, w.cluster.starts);
    }

    @Test
    void startThatDoesNotReachReadyIn15MinutesFailsAndIsNotCounted() {
        String id = start("u1");
        w.cluster.running();
        w.cluster.view = new ClusterView(true, List.of(), List.of(), 0);
        w.clock.plus(Duration.ofMinutes(14));
        w.engine.tick();
        assertEquals("starting", session(id).state);
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        Session s = session(id);
        assertEquals("failed", s.state);
        assertEquals("error", s.last("node").status());
        assertEquals(0, w.users.rows.get("u1").windowsUsed);
        assertNull(w.users.rows.get("u1").activeSessionId);
        assertEquals(1, w.cluster.stops, "the cluster is stopped after a failed start");
    }

    @Test
    void clusterThatAlreadyRunsIsNotStartedAgain() {
        w.cluster.running();
        String id = start("u1");
        assertEquals(0, w.cluster.starts);
        assertTrue(session(id).last("arm-start").detail().contains("already runs"));
        w.cluster.view = World.readyView();
        w.upstream.readiness = 200;
        w.engine.tick();
        assertEquals("ready", session(id).state);
    }

    @Test
    void refusedStartIsSentAgainByTheTimer() {
        w.cluster.accept = false;
        String id = start("u1");
        assertEquals("active", session(id).last("arm-start").status());
        w.cluster.accept = true;
        w.engine.tick();
        assertTrue(session(id).done("arm-start"));
        assertEquals(2, w.cluster.starts);
    }

    @Test
    void adminPauseRefusesNewWindowsButRunningWindowsEndNormally() {
        String id = start("u1");
        w.makeReady();
        GatewayResponse p = w.handlers.admin(World.req("POST", "pause", "good-" + World.ADMIN, null, "{\"paused\":true}"));
        assertEquals(200, p.status());
        assertTrue(World.json(p).get("paused").asBoolean());
        assertEquals("admin", World.json(p).get("engine").get("pausedReason").asText());
        assertEquals("running", World.json(p).get("engine").get("state").asText());
        assertEquals("ready", session(id).state);

        w.signUp("u2", "198.51.100.2", "fingerprint-u2");
        assertEquals("paused", World.json(w.createSession("u2")).get("code").asText());

        w.clock.plus(Duration.ofMinutes(15));
        w.engine.tick();
        assertEquals("ended", session(id).state);
        assertEquals(1, w.cluster.stops);
        w.cluster.stopped();
        w.engine.tick();
        assertEquals("paused", w.engine.view().state());

        w.handlers.admin(World.req("POST", "pause", "good-" + World.ADMIN, null, "{\"paused\":false}"));
        assertEquals("cooldown", w.engine.view().state());
        assertTrue(w.audit.contains("engine.pause.admin"));
        assertTrue(w.audit.contains("engine.resume"));
    }

    @Test
    void budgetHookPausesAndStopsTheClusterNow() {
        String id = start("u1");
        w.makeReady();
        GatewayResponse r = w.handlers.budgetHook(World.req("POST", null, null, null,
                "{\"data\":{\"SpendingAmount\":\"61.2\",\"Budget\":\"60\"}}"));
        assertEquals(200, r.status());
        assertEquals("ended", session(id).state);
        assertEquals(1, w.cluster.stops);
        Views.Engine view = w.engine.view();
        assertEquals("budget", view.pausedReason());
        assertEquals("stopping", view.state());
        w.cluster.stopped();
        w.engine.tick();
        assertEquals("paused", w.engine.view().state());
        assertEquals("paused", World.json(w.createSession("u1")).get("code").asText());
    }

    @Test
    void earlyBudgetAlertChangesNothing() {
        w.handlers.budgetHook(World.req("POST", null, null, null, "{\"data\":{\"SpendingAmount\":30,\"Budget\":60}}"));
        assertFalse(w.engineRow.paused);
        // L3: a body that is not a budget alert is refused and pauses nothing (the timer's cost check stays).
        for (String body : new String[] {"not json", "{}", "{\"data\":{\"SpendingAmount\":\"x\",\"Budget\":60}}"}) {
            assertEquals(400, w.handlers.budgetHook(World.req("POST", null, null, null, body)).status());
        }
        assertFalse(w.engineRow.paused);
        assertTrue(w.audit.contains("budget.alert.unreadable"));
    }

    @Test
    void adminStopEndsWindowsAndStopsTheCluster() {
        String id = start("u1");
        w.makeReady();
        GatewayResponse r = w.handlers.admin(World.req("POST", "engine/stop", "good-" + World.ADMIN, null, null));
        assertEquals(200, r.status());
        assertEquals("stopping", World.json(r).get("engine").get("state").asText());
        assertEquals("ended", session(id).state);
        assertEquals(1, w.cluster.stops);
        assertTrue(w.audit.contains("engine.force-stop"));
    }

    @Test
    void clusterStartedBySomethingElseGetsTwentyMinutesBeforeTheStop() {
        w.cluster.running();
        w.engine.tick();
        assertEquals(0, w.cluster.stops);
        assertEquals("running", w.engine.view().state());
        w.clock.plus(Duration.ofMinutes(19));
        w.engine.tick();
        assertEquals(0, w.cluster.stops);
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        assertEquals(1, w.cluster.stops);
        assertEquals("stopping", w.engine.view().state());
    }

    @Test
    void unknownPowerStateChangesNothing() {
        w.cluster.power = PowerState.UNKNOWN;
        w.engine.tick();
        assertEquals(0, w.cluster.stops);
        assertEquals("stopped", w.engine.view().state());
    }

    @Test
    void engineRouteIsPublicAndHasTheContractShape() {
        JsonNode e = World.json(w.handlers.engine(World.req("GET", null, null, null, null)));
        assertEquals("stopped", e.get("state").asText());
        assertEquals(0, e.get("activeSessions").asInt());
        assertTrue(e.has("since"));
        assertFalse(e.has("pausedReason"));
    }

    @Test
    void perUserGuardLimitsSessionRequests() {
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        w.users.rows.get("u1").windowsUsed = 3;
        for (int i = 0; i < 5; i++) assertEquals(409, w.createSession("u1").status());
        assertEquals(429, w.createSession("u1").status());
    }
}
