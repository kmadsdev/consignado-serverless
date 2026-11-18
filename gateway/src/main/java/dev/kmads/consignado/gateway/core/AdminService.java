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
    private final UserErasure erasure;
    private final Hasher hasher;

    /** The answer of the action "delete". The Entra ID guest account stays: the gateway has no directory rights. */
    public record Deleted(boolean deleted, String entraAccount) {}

    public AdminService(UserStore users, EngineService engine, CostReader cost, AuditLog audit, Settings settings,
                        Clock clock, UserErasure erasure, Hasher hasher) {
        this.erasure = erasure;
        this.hasher = hasher;
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

    /**
     * body: {action: approve | hold | block | grant | delete, windows?}. Returns Views.AdminUser, or Deleted
     * for the action delete.
     */
    public Object act(String adminId, String userId, JsonNode body, String ipHash) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("A JSON body is required");
        String action = body.path("action").asText("");
        String id = Validate.userId(userId);
        if (action.equals("delete")) return delete(adminId, id, ipHash);
        User user = UserRows.update(users, id, u -> apply(u, action, body)).orElseThrow(ApiException::notFound);
        audit.record(adminId, "admin.user." + action, user.id, ipHash);
        return Views.adminUser(user, settings);
    }

    /** The change of one admin action. Applied to the newest row (again after a conflict). */
    private static void apply(User user, String action, JsonNode body) {
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
            case "set" -> {
                Integer max = intField(body, "windowsMax", 0, 50);
                Integer minutes = intField(body, "windowMinutes", 5, 60);
                if (max == null && minutes == null) {
                    throw ApiException.badRequest("Field windowsMax or windowMinutes is required");
                }
                if (max != null) user.windowsMax = max;
                if (minutes != null) user.windowMinutes = minutes;
            }
            default -> throw ApiException.badRequest(
                    "Field action must be approve, hold, block, grant, set or delete");
        }
    }

    /** null when the field is absent; 400 when it is not a whole number in [min, max]. */
    private static Integer intField(JsonNode body, String name, int min, int max) {
        JsonNode n = body.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.isInt() || n.asInt() < min || n.asInt() > max) {
            throw ApiException.badRequest("Field " + name + " must be a whole number from " + min + " to " + max);
        }
        return n.asInt();
    }

    /**
     * Deletion on request (LGPD article 18): the users row, the sessions rows and the contacts rows of the user.
     * The audit row has a hash of the user id, never the id or the email. An open window of the user ends with
     * its row; the timer then stops the cluster when nothing else is active. The admin cannot delete himself.
     */
    private Deleted delete(String adminId, String userId, String ipHash) {
        if (userId.equals(adminId) || userId.equals(settings.adminOid())) {
            throw ApiException.conflict("self-delete", "The owner account cannot be deleted here");
        }
        User user = users.find(userId).orElseThrow(ApiException::notFound);
        String target = hasher.hash(user.id);
        erasure.erase(user);
        audit.record(adminId, "admin.user.delete", target, ipHash);
        return new Deleted(true, "delete it in the Entra admin center");
    }

    /** The timer's budget guard (every 30 minutes). */
    public boolean checkBudgetIfDue() {
        return engine.checkBudgetIfDue(cost);
    }

    public Views.AdminState state() {
        Views.Engine view = engine.view();
        Instant now = clock.instant();
        // When the cost API does not answer, the value is unknown: asOf is the epoch, not "now".
        Views.Budget budget = cost.monthToDate()
                .map(c -> Views.Budget.of(settings.budgetLimit(), c.amount(),
                        c.currency() != null ? c.currency() : settings.budgetCurrency(), Times.iso(c.asOf())))
                .orElseGet(() -> Views.Budget.of(settings.budgetLimit(), 0.0, settings.budgetCurrency(),
                        Times.iso(Instant.EPOCH)));
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
