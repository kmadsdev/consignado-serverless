package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.ContactStore;
import dev.kmads.consignado.gateway.ports.SessionStore;
import dev.kmads.consignado.gateway.ports.UserStore;

/**
 * Deletes the personal data of one user in the gateway tables: the users row, the sessions rows and the
 * contacts rows (same email or same user id). Used by the admin action "delete" (LGPD article 18) and by
 * the retention job. The Entra ID account is not touched: the gateway has no directory rights.
 * The caller writes the audit row (with a hash, never the id or the email).
 */
public final class UserErasure {
    public record Result(int sessions, int contacts) {}

    private final UserStore users;
    private final SessionStore sessions;
    private final ContactStore contacts;

    public UserErasure(UserStore users, SessionStore sessions, ContactStore contacts) {
        this.users = users;
        this.sessions = sessions;
        this.contacts = contacts;
    }

    public Result erase(User user) {
        int s = 0;
        for (Session session : sessions.list()) {
            if (user.id.equals(session.userId)) {
                sessions.delete(session.id);
                s++;
            }
        }
        int c = 0;
        String email = user.email == null ? "" : user.email.strip();
        for (Contact contact : contacts.list()) {
            boolean sameUser = user.id.equals(contact.userId());
            boolean sameEmail = !email.isEmpty() && email.equalsIgnoreCase(contact.email());
            if (sameUser || sameEmail) {
                contacts.delete(contact.id());
                c++;
            }
        }
        users.delete(user.id);
        return new Result(s, c);
    }
}
