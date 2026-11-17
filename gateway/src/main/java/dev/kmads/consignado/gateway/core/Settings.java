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
        Duration idleGrace) {

    public static Settings defaults(String adminOid) {
        return new Settings(adminOid, Duration.ofMinutes(15), 3, Duration.ofMinutes(15), 60.0,
                Duration.ofMinutes(15), Duration.ofMinutes(20));
    }
}
