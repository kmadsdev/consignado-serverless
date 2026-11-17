package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** The JSON bodies of the gateway API. Field names equal front/src/api/types.ts. */
public final class Views {
    private Views() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Me(String id, String email, String name, String role, boolean profileComplete, String status,
                     int windowsUsed, int windowsMax, String activeSessionId, int windowMinutes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUser(String id, String email, String name, String role, boolean profileComplete,
                            String status, int windowsUsed, int windowsMax, String activeSessionId, String company,
                            String linkedin, String createdAt, String lastSeenAt, List<String> flags,
                            int windowMinutes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    /**
     * paused is true whenever new windows are paused, also while a window still runs (state then stays
     * "running" or "starting"; running windows end normally).
     */
    public record Engine(String state, String since, int activeSessions, String pausedReason, boolean paused) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionView(String id, String userId, String state, String requestedAt, String readyAt,
                              String expiresAt, List<LifecycleEvent> events, int requests) {}

    public record Budget(double limitUsd, double spentUsd, String asOf) {}

    public record AdminState(Engine engine, Budget budget, boolean paused) {}

    public record RequestTrace(String host, boolean cold, long totalMs, List<LifecycleEvent> events) {}

    public record ErrorBody(String code, String message) {}

    /** GET /api/status (contract addendum, part 1). */
    public record PlatformStatus(String asOf, String host, List<ServiceStatus> services) {}

    public record ServiceStatus(String id, String name, String kind,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String region, String state,
                                String detail, List<Metric> metrics, List<String> source) {}

    /** value null = Azure returned no data for the window. */
    public record Metric(String key, String label, Double value, String unit, String window) {}

    public static String role(User u, Settings settings) {
        return u.id.equals(settings.adminOid()) ? "admin" : "user";
    }

    public static Me me(User u, Settings settings) {
        return new Me(u.id, u.email, u.name, role(u, settings), u.profileComplete(), u.status, u.windowsUsed,
                u.windowsMax, u.activeSessionId, u.windowMinutes(settings));
    }

    public static AdminUser adminUser(User u, Settings settings) {
        return new AdminUser(u.id, u.email, u.name, role(u, settings), u.profileComplete(), u.status,
                u.windowsUsed, u.windowsMax, u.activeSessionId, u.company, u.linkedin, Times.iso(u.createdAt),
                Times.iso(u.lastSeenAt), List.copyOf(u.flags), u.windowMinutes(settings));
    }

    public static SessionView session(Session s) {
        return new SessionView(s.id, s.userId, s.state, Times.iso(s.requestedAt), Times.iso(s.readyAt),
                Times.iso(s.expiresAt), List.copyOf(s.events), s.requests);
    }
}
