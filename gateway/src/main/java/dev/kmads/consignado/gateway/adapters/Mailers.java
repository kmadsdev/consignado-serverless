package dev.kmads.consignado.gateway.adapters;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.credential.TokenCredential;
import dev.kmads.consignado.gateway.core.GuardedMailer;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.Mailer;

/** The two mailers: Azure Communication Services, and "audit only" when ACS_ENDPOINT is not set. */
public final class Mailers {
    private Mailers() {}

    /** Writes an audit row with the subject. The text is not stored: it can hold personal data. */
    public static Mailer auditOnly(AuditLog audit) {
        return (subject, text) -> audit.record("gateway", "mail.not-sent", subject, null);
    }

    /**
     * ACS email with the managed identity (role "Communication and Email Service Owner" on the ACS resource).
     * The client is built at the first mail, not at the start: a bad endpoint cannot stop the app.
     * GuardedMailer catches every failure and writes the log line and the audit row.
     */
    public static Mailer acs(String endpoint, String sender, String ownerEmail, TokenCredential credential,
                             AuditLog audit) {
        Lazy lazy = new Lazy(endpoint, credential);
        return new GuardedMailer((subject, text) -> {
            EmailMessage message = new EmailMessage().setSenderAddress(sender).setToRecipients(ownerEmail)
                    .setSubject(subject).setBodyPlainText(text);
            // beginSend makes the POST now; ACS answers 202 when it accepted the mail. The gateway does not
            // wait for the delivery (ACS metric DeliveryStatusUpdate shows it).
            lazy.client().beginSend(message);
        }, audit);
    }

    private static final class Lazy {
        private final String endpoint;
        private final TokenCredential credential;
        private EmailClient client;

        Lazy(String endpoint, TokenCredential credential) {
            this.endpoint = endpoint;
            this.credential = credential;
        }

        synchronized EmailClient client() {
            if (client == null) {
                client = new EmailClientBuilder().endpoint(endpoint).credential(credential).buildClient();
            }
            return client;
        }
    }
}
