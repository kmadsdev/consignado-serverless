package dev.kmads.consignado.gateway.core;

import java.time.Duration;

/** The numbers and ids that the core rules need. Built from Config in production. */
public record Settings(
        String adminOid,
        Duration window,
        int windowsMax,
        Duration cooldown,
        double budgetLimitUsd,
        Duration startTimeout,
        Duration idleGrace,
        /** Cost guard over all accounts: new access windows in any 24 hours (the owner is not counted against it). */
        int dailyWindowsMax) {

    public static final int DEFAULT_DAILY_WINDOWS_MAX = 12;

    public static Settings defaults(String adminOid) {
        return new Settings(adminOid, Duration.ofMinutes(15), 3, Duration.ofMinutes(15), 60.0,
                Duration.ofMinutes(15), Duration.ofMinutes(20), DEFAULT_DAILY_WINDOWS_MAX);
    }
}
