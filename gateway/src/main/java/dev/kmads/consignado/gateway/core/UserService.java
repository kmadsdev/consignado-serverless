package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.BotCheck;
import dev.kmads.consignado.gateway.ports.Mailer;
import dev.kmads.consignado.gateway.ports.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** The user row: first sign-in, profile, duplicate check. */
public final class UserService {
    public static final String FLAG_SAME_DEVICE = "same-device";
    public static final String FLAG_SAME_IP = "same-ip";
    public static final String FLAG_DISPOSABLE = "disposable-email";

    private final UserStore users;
    private final Mailer mailer;
    private final BotCheck botCheck;
    private final Hasher hasher;
    private final AuditLog audit;
    private final Settings settings;
    private final Clock clock;

    public UserService(UserStore users, Mailer mailer, BotCheck botCheck, Hasher hasher, AuditLog audit,
                       Settings settings, Clock clock) {
        this.users = users;
        this.mailer = mailer;
        this.botCheck = botCheck;
        this.hasher = hasher;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    public boolean isAdmin(Claims claims) {
        return claims != null && claims.oid().equals(settings.adminOid());
    }

    /** Returns the user of the token. Creates the row on the first call and tells the owner. */
    public User me(Claims claims, String clientIp) {
        Instant now = clock.instant();
        User user = users.find(claims.oid()).orElse(null);
        if (user == null) {
            user = new User();
            user.id = claims.oid();
            user.email = safe(claims.email(), 254);
            user.name = safe(claims.name(), 80);
            user.windowsMax = settings.windowsMax();
            user.createdAt = now;
            user.lastSeenAt = now;
            user.ipHash = hasher.hash(clientIp);
            if (Validate.disposableEmail(user.email)) user.flags.add(FLAG_DISPOSABLE);
            users.save(user);
            audit.record(user.id, "user.create", user.id, user.ipHash);
            mailer.sendToOwner("Consignado: new user",
                    "A new user signed in.\nName: " + user.name + "\nEmail: " + user.email + "\nId: " + user.id);
            return user;
        }
        boolean changed = false;
        if (user.email.isBlank() && claims.email() != null && !claims.email().isBlank()) {
            user.email = safe(claims.email(), 254);
            changed = true;
        }
        if (user.lastSeenAt == null || Duration.between(user.lastSeenAt, now).toSeconds() >= 60) {
            user.lastSeenAt = now;
            changed = true;
        }
        if (changed) users.save(user);
        return user;
    }

    /** Body: Profile + {fingerprint, turnstileToken}. Runs the bot check and the duplicate check. */
    public User updateProfile(Claims claims, JsonNode body, String clientIp) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("A JSON body is required");
        String name = Validate.singleLine("name", text(body, "name"), 2, 80);
        String company = Validate.singleLine("company", text(body, "company"), 2, 100);
        String linkedin = Validate.linkedin(text(body, "linkedin"));
        if (!body.path("consent").asBoolean(false)) {
            throw ApiException.badRequest("Field consent must be true");
        }
        String fingerprint = Validate.fingerprint(text(body, "fingerprint"));
        String token = Validate.botToken(text(body, "turnstileToken"));
        if (!botCheck.verify(token, clientIp)) {
            throw new ApiException(403, "bot-check-failed", "The bot check did not pass. Try again.");
        }

        Instant now = clock.instant();
        User user = me(claims, clientIp);
        user.name = name;
        user.company = company;
        user.linkedin = linkedin;
        if (user.consentAt == null) user.consentAt = now;
        user.fingerprintHash = hasher.hash(fingerprint);
        user.ipHash = hasher.hash(clientIp);
        user.lastSeenAt = now;

        boolean sameDevice = false;
        boolean sameIp = false;
        for (User other : users.list()) {
            if (other.id.equals(user.id)) continue;
            if (user.fingerprintHash != null && user.fingerprintHash.equals(other.fingerprintHash)) sameDevice = true;
            if (user.ipHash != null && user.ipHash.equals(other.ipHash)) sameIp = true;
        }
        boolean heldNow = false;
        if (sameDevice) {
            user.flags.add(FLAG_SAME_DEVICE);
            if (User.ACTIVE.equals(user.status) && !user.approvedByAdmin && !isAdmin(claims)) {
                user.status = User.HELD;
                heldNow = true;
            }
        } else if (sameIp) {
            user.flags.add(FLAG_SAME_IP);
        }
        users.save(user);
        audit.record(user.id, "profile.update", user.id, user.ipHash);
        if (heldNow) {
            audit.record("gateway", "user.hold", user.id, user.ipHash);
            mailer.sendToOwner("Consignado: account held",
                    "The duplicate check held an account (same device as another account).\nName: " + user.name
                            + "\nEmail: " + user.email + "\nId: " + user.id);
        }
        return user;
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() || !n.isTextual() ? null : n.asText();
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String v = value.replaceAll("\\p{Cntrl}", " ").strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
