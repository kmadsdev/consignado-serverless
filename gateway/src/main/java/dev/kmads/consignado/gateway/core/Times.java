package dev.kmads.consignado.gateway.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Times {
    private Times() {}

    /** ISO 8601 UTC with milliseconds, or null. */
    public static String iso(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    public static Instant parse(String s) {
        return s == null || s.isBlank() ? null : Instant.parse(s);
    }
}
