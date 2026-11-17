package dev.kmads.consignado.gateway.core;

import java.time.Instant;

/** The single row of the engine table. */
public final class EngineRecord {
    public static final String STOPPED = "stopped";
    public static final String STARTING = "starting";
    public static final String RUNNING = "running";
    public static final String STOPPING = "stopping";
    public static final String COOLDOWN = "cooldown";
    public static final String PAUSED = "paused";

    public String state = STOPPED;
    public Instant since;
    public boolean paused;
    public String pausedReason;
    public Instant lastStopAt;
    public int activeSessions;
    /** Set when the cluster runs but no window asked for it (for example a CI deploy started it). */
    public Instant externalSince;
    public Instant lastSnapshotAt;
}
