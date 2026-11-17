package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.BotCheck;
import dev.kmads.consignado.gateway.ports.ContactStore;
import dev.kmads.consignado.gateway.ports.Mailer;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/** POST /api/contact: validate, bot check, save, email to the owner. */
public final class ContactService {
    private final ContactStore contacts;
    private final Mailer mailer;
    private final BotCheck botCheck;
    private final Hasher hasher;
    private final AuditLog audit;
    private final Clock clock;
    private final Supplier<String> ids;

    public ContactService(ContactStore contacts, Mailer mailer, BotCheck botCheck, Hasher hasher, AuditLog audit,
                          Clock clock, Supplier<String> ids) {
        this.contacts = contacts;
        this.mailer = mailer;
        this.botCheck = botCheck;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
        this.ids = ids;
    }

    public Contact create(JsonNode body, String clientIp, String who) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("A JSON body is required");
        String kind = body.path("kind").asText("contact");
        if (!kind.equals("contact") && !kind.equals("more-windows")) {
            throw ApiException.badRequest("Field kind must be contact or more-windows");
        }
        String name = Validate.singleLine("name", textOf(body, "name"), 2, 80);
        String email = Validate.email(textOf(body, "email"));
        String message = Validate.text("message", textOf(body, "message"), 1, 2000);
        String token = Validate.botToken(textOf(body, "turnstileToken"));
        if (!botCheck.verify(token, clientIp)) {
            throw new ApiException(403, "bot-check-failed", "The bot check did not pass. Try again.");
        }
        // A signed-in sender is linked by the user id: the owner sees whose "more windows" request it is.
        Contact contact = new Contact(ids.get(), kind, name, email, message, Times.iso(clock.instant()), who);
        contacts.save(contact);
        audit.record(who == null ? "guest" : who, "contact.create", contact.id(), hasher.hash(clientIp));
        mailer.sendToOwner("Consignado: " + (kind.equals("contact") ? "contact request" : "request for more windows"),
                "From: " + name + " <" + email + ">\nKind: " + kind
                        + (who == null ? "\nSigned in: no" : "\nUser id: " + who) + "\n\n" + message);
        return contact;
    }

    public List<Contact> list() {
        return contacts.list();
    }

    private static String textOf(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || !n.isTextual() ? null : n.asText();
    }
}
