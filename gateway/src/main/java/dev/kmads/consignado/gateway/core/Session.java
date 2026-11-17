package dev.kmads.consignado.gateway.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One row of the sessions table: a 15-minute access window to the AKS host. */
public final class Session {
    public static final String STARTING = "starting";
    public static final String READY = "ready";
    public static final String ENDED = "ended";
    public static final String FAILED = "failed";
    public static final int MAX_EVENTS = 120;

    public String id;
    public String userId;
    public String state = STARTING;
    public Instant requestedAt;
    public Instant readyAt;
    public Instant expiresAt;
    public Instant endedAt;
    public Instant lastObservedAt;
    public List<LifecycleEvent> events = new ArrayList<>();
    /** True until the final cluster-stop event is recorded. The timer reads only open sessions. */
    public boolean open = true;
    /** Proxied AKS calls of this window that the API host answered with 2xx or 3xx. */
    public int requests;
    /** Storage version for optimistic concurrency. Null for a new row. */
    public String etag;

    public boolean active() {
        return STARTING.equals(state) || READY.equals(state);
    }

    public boolean done(String stage) {
        return has(stage, LifecycleEvent.DONE);
    }

    public boolean has(String stage, String status) {
        for (LifecycleEvent e : events) {
            if (e.stage().equals(stage) && e.status().equals(status)) return true;
        }
        return false;
    }

    public LifecycleEvent last(String stage) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).stage().equals(stage)) return events.get(i);
        }
        return null;
    }

    public LifecycleEvent lastEvent() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    public void add(Instant now, String stage, String status, String detail, Long ms) {
        if (events.size() >= MAX_EVENTS) return;
        events.add(new LifecycleEvent(Times.iso(now), stage, status, detail, ms));
    }

    /** Adds an event unless the newest event of the stage already says the same thing. */
    public void addOnce(Instant now, String stage, String status, String detail) {
        LifecycleEvent last = last(stage);
        if (last != null && last.status().equals(status) && java.util.Objects.equals(last.detail(), detail)) return;
        add(now, stage, status, detail, null);
    }
}
