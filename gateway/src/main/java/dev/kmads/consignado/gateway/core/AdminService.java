package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.CostReader;
import dev.kmads.consignado.gateway.ports.UserStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** The admin routes. Every change writes an audit row. */
public final class AdminService {
    private final UserStore users;
    private final EngineService engine;
    private final CostReader cost;
    private final AuditLog audit;
    private final Settings settings;
    private final Clock clock;

    public AdminService(UserStore users, EngineService engine, CostReader cost, AuditLog audit, Settings settings,
                        Clock clock) {
        this.users = users;
        this.engine = engine;
        this.cost = cost;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    public List<Views.AdminUser> listUsers() {
        return users.list().stream()
                .sorted(Comparator.comparing((User u) -> u.createdAt == null ? Instant.EPOCH : u.createdAt).reversed())
                .map(u -> Views.adminUser(u, settings)).toList();
    }

    /** body: {action: approve | hold | block | grant, windows?} */
    public Views.AdminUser act(String adminId, String userId, JsonNode body, String ipHash) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("A JSON body is required");
        String action = body.path("action").asText("");
        User user = users.find(Validate.userId(userId)).orElseThrow(ApiException::notFound);
        switch (action) {
            case "approve" -> {
                user.status = User.ACTIVE;
                user.approvedByAdmin = true;
            }
            case "hold" -> {
                user.status = User.HELD;
                user.approvedByAdmin = false;
            }
            case "block" -> user.status = User.BLOCKED;
            case "grant" -> {
                JsonNode w = body.get("windows");
                int windows = w == null || w.isNull() ? 1 : (w.isInt() ? w.asInt() : -1);
                if (windows < 1 || windows > 20) throw ApiException.badRequest("Field windows must be 1 to 20");
                user.windowsMax = Math.min(1000, user.windowsMax + windows);
            }
            default -> throw ApiException.badRequest("Field action must be approve, hold, block or grant");
        }
        users.save(user);
        audit.record(adminId, "admin.user." + action, user.id, ipHash);
        return Views.adminUser(user, settings);
    }

    public Views.AdminState state() {
        Views.Engine view = engine.view();
        Instant now = clock.instant();
        // When the cost API does not answer, the value is unknown: asOf is the epoch, not "now".
        Views.Budget budget = cost.monthToDate()
                .map(c -> new Views.Budget(settings.budgetLimitUsd(), c.usd(), Times.iso(c.asOf())))
                .orElseGet(() -> new Views.Budget(settings.budgetLimitUsd(), 0.0, Times.iso(Instant.EPOCH)));
        return new Views.AdminState(view, budget, view.pausedReason() != null);
    }

    public Views.AdminState pause(String adminId, JsonNode body, String ipHash) {
        if (body == null || !body.has("paused") || !body.get("paused").isBoolean()) {
            throw ApiException.badRequest("Field paused must be true or false");
        }
        engine.pause(body.get("paused").asBoolean(), "admin", adminId, ipHash);
        return state();
    }

    public Views.AdminState stopEngine(String adminId, String ipHash) {
        engine.forceStop(adminId, ipHash, "stopped by the owner");
        return state();
    }
}
