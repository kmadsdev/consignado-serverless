package dev.kmads.consignado.gateway.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Input checks. Every message is generic and names only the field. */
public final class Validate {
    private Validate() {}

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.\\-]{0,251}[A-Za-z0-9])?\\.[A-Za-z]{2,24}$");
    private static final Pattern LINKEDIN =
            Pattern.compile("^https://(?:[a-z]{2,3}\\.)?linkedin\\.com/in/[A-Za-z0-9\\-_%.]{3,100}/?$");
    private static final Pattern FINGERPRINT = Pattern.compile("^[A-Za-z0-9_\\-:.+/=]{8,128}$");
    private static final Pattern SESSION_ID = Pattern.compile("^[a-z0-9]{8,40}$");
    private static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9\\-]{1,64}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    private static final Set<String> DISPOSABLE = Set.of(
            "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com", "temp-mail.org",
            "yopmail.com", "trashmail.com", "sharklasers.com", "getnada.com", "dispostable.com",
            "throwawaymail.com", "maildrop.cc", "fakeinbox.com", "tempmailo.com", "emailondeck.com");

    public static String text(String field, String value, int min, int max) {
        if (value == null) throw ApiException.badRequest("Field " + field + " is required");
        String v = value.strip();
        if (v.length() < min || v.length() > max || CONTROL.matcher(v).find()) {
            throw ApiException.badRequest("Field " + field + " must have " + min + " to " + max + " characters");
        }
        return v;
    }

    public static String singleLine(String field, String value, int min, int max) {
        String v = text(field, value, min, max);
        if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            throw ApiException.badRequest("Field " + field + " must be one line");
        }
        return v;
    }

    public static String email(String value) {
        String v = singleLine("email", value, 6, 254);
        if (!EMAIL.matcher(v).matches() || v.contains("..")) {
            throw ApiException.badRequest("Field email is not a valid email address");
        }
        return v;
    }

    public static String linkedin(String value) {
        String v = singleLine("linkedin", value, 20, 200);
        if (!LINKEDIN.matcher(v).matches()) {
            throw ApiException.badRequest("Field linkedin must be a URL like https://www.linkedin.com/in/your-name");
        }
        return v;
    }

    public static String fingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw ApiException.badRequest("Field fingerprint is not valid");
        }
        return value;
    }

    public static String botToken(String value) {
        if (value == null) return "";
        if (value.length() > 2048) throw ApiException.badRequest("Field turnstileToken is not valid");
        return value;
    }

    public static String sessionId(String value) {
        if (value == null || !SESSION_ID.matcher(value).matches()) throw ApiException.notFound();
        return value;
    }

    public static String userId(String value) {
        if (value == null || !USER_ID.matcher(value).matches()) throw ApiException.notFound();
        return value;
    }

    public static boolean disposableEmail(String email) {
        if (email == null) return false;
        int at = email.lastIndexOf('@');
        return at >= 0 && DISPOSABLE.contains(email.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
