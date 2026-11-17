package dev.kmads.consignado.gateway.core;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/** One row of the users table. */
public final class User {
    public static final String ACTIVE = "active";
    public static final String HELD = "held";
    public static final String BLOCKED = "blocked";

    public String id;
    public String email = "";
    public String name = "";
    public String company = "";
    public String linkedin = "";
    public String status = ACTIVE;
    public int windowsUsed;
    public int windowsMax;
    /** Length of this user's next windows; 0 = the default WINDOW_MINUTES (rows made before this column). */
    public int windowMinutes;
    public Set<String> flags = new TreeSet<>();
    public String ipHash;
    public String fingerprintHash;
    public Instant createdAt;
    public Instant lastSeenAt;
    public Instant consentAt;
    /** The admin approved this account after a hold: the duplicate check does not hold it again. */
    public boolean approvedByAdmin;
    public String activeSessionId;
    /** HMAC of the normalised mailbox (Validate.mailbox): the duplicate check across accounts. */
    public String mailboxHash;
    /** Storage version for optimistic concurrency. Null for a new row. */
    public String etag;

    public int windowMinutes(Settings settings) {
        return windowMinutes > 0 ? windowMinutes : (int) settings.window().toMinutes();
    }

    public boolean profileComplete() {
        return !name.isBlank() && !company.isBlank() && !linkedin.isBlank() && consentAt != null;
    }
}
