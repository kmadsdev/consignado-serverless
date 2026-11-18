package dev.kmads.consignado.gateway.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.kmads.consignado.gateway.fakes.World;
import dev.kmads.consignado.gateway.http.GatewayResponse;
import dev.kmads.consignado.gateway.ports.EngineStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * M4: the engine row is written with its ETag. World.afterEngineLoad puts another writer between a read of
 * the row and its write (the admin, the budget webhook, or a second gateway instance).
 */
class EngineRowConcurrencyTest {
    private final World w = new World();

    private String readyWindowThatExpires() {
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        GatewayResponse r = w.createSession("u1");
        assertEquals(201, r.status(), new String(r.body()));
        w.makeReady();
        w.clock.plus(Duration.ofMinutes(15));
        return World.json(r).get("id").asText();
    }

    @Test
    void anAdminPauseBetweenTheTimersLoadAndSaveSurvives() {
        readyWindowThatExpires();
        w.afterEngineLoad = () -> w.engine.pause(true, "admin", World.ADMIN, null);
        w.engine.tick();
        assertTrue(w.engineRow.paused, "the timer must never clear a pause");
        assertEquals("admin", w.engineRow.pausedReason);
        assertEquals("stopping", w.engineRow.state, "the timer still applied its own change");
        assertEquals(1, w.cluster.stops);
    }

    @Test
    void aBudgetPauseBetweenTheTimersLoadAndSaveSurvives() {
        readyWindowThatExpires();
        w.afterEngineLoad = () -> w.engine.pause(true, "budget", "budget-alert", null);
        w.engine.tick();
        assertTrue(w.engineRow.paused);
        assertEquals("budget", w.engineRow.pausedReason);
        assertTrue(w.engine.pausedForBudget());
    }

    @Test
    void aTimerWriteBetweenThePausesLoadAndSaveDoesNotLoseThePause() {
        readyWindowThatExpires();
        w.afterEngineLoad = () -> w.engine.tick();
        w.engine.pause(true, "admin", World.ADMIN, null);
        assertTrue(w.engineRow.paused);
        assertEquals("admin", w.engineRow.pausedReason);
        assertEquals("stopping", w.engineRow.state, "the pause did not undo the timer's state");
    }

    @Test
    void twoTimerRunsDoNotBothStopTheCluster() {
        readyWindowThatExpires();
        // Instance B runs a whole tick after instance A read ARM and the row, before A writes.
        w.afterEngineLoad = () -> w.engine.tick();
        w.engine.tick();
        assertEquals(1, w.cluster.stops);
        assertEquals("stopping", w.engineRow.state);

        // ARM does not show the stop yet: a next tick (any instance) does not send a second one.
        w.cluster.running();
        w.clock.plus(Duration.ofSeconds(60));
        w.engine.tick();
        assertEquals(1, w.cluster.stops);

        // After 2 minutes without a stop on ARM, the stop is sent again (the old retry stays).
        w.clock.plus(Duration.ofSeconds(61));
        w.engine.tick();
        assertEquals(2, w.cluster.stops);
    }

    @Test
    void twoTimerRunsDoNotBothStartTheCluster() {
        w.cluster.accept = false;
        w.signUp("u1", "203.0.113.1", "fingerprint-u1");
        assertEquals(201, w.createSession("u1").status());
        assertEquals(1, w.cluster.starts);
        w.cluster.accept = true;
        w.clock.plus(Duration.ofSeconds(10));
        w.afterEngineLoad = () -> w.engine.tick();
        w.engine.tick();
        assertEquals(2, w.cluster.starts, "one start again, not one per instance");
        assertNotNull(w.engineRow.lastStartAt);
    }

    @Test
    void aRefusedStopGivesTheClaimBackAndTheNextTickTriesAgain() {
        readyWindowThatExpires();
        w.cluster.accept = false;
        w.engine.tick();
        assertEquals(1, w.cluster.stops);
        assertEquals("running", w.engineRow.state, "a refused stop changes nothing");
        assertTrue(w.audit.contains("engine.stop.refused"));
        w.cluster.accept = true;
        w.clock.plus(Duration.ofMinutes(1));
        w.engine.tick();
        assertEquals(2, w.cluster.stops);
        assertEquals("stopping", w.engineRow.state);
    }

    @Test
    void aStaleSaveIsRefusedByTheStore() {
        EngineStore store = storeOf(w);
        EngineRecord a = store.load();
        EngineRecord b = store.load();
        a.paused = true;
        store.save(a);
        b.state = EngineRecord.STARTING;
        assertThrows(EngineStore.ConflictException.class, () -> store.save(b));
        assertTrue(w.engineRow.paused);
    }

    @Test
    void aRowThatAlwaysChangesGivesBusyAfterFiveAttempts() {
        EngineStore store = storeOf(w);
        EngineStore busy = new EngineStore() {
            @Override public EngineRecord load() { return store.load(); }
            @Override public void save(EngineRecord r) { throw new ConflictException(); }
        };
        ApiException e = assertThrows(ApiException.class, () -> EngineRows.update(busy, r -> r.paused = true));
        assertEquals(409, e.status());
        assertEquals("busy", e.code());
    }

    @Test
    void retentionRunsOnceWhenTwoInstancesAreDue() {
        w.afterEngineLoad = () -> assertNotNull(w.retention.runIfDue());
        assertNull(w.retention.runIfDue(), "the instance that wrote second does not run the job");
        assertNotNull(w.engineRow.lastRetentionAt);
    }

    private static EngineStore storeOf(World w) {
        return w.engineStore;
    }
}
