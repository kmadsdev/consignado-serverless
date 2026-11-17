package dev.kmads.consignado.gateway.adapters;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.credential.TokenCredential;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.Mailer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The two mailers: Azure Communication Services, and "audit only" when ACS_ENDPOINT is not set. */
public final class Mailers {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");

    private Mailers() {}

    /** Writes an audit row with the subject. The text is not stored: it can hold personal data. */
    public static Mailer auditOnly(AuditLog audit) {
        return (subject, text) -> audit.record("gateway", "mail.not-sent", subject, null);
    }

    public static Mailer acs(String endpoint, String sender, String ownerEmail, TokenCredential credential,
                             AuditLog audit) {
        EmailClient client = new EmailClientBuilder().endpoint(endpoint).credential(credential).buildClient();
        return (subject, text) -> {
            try {
                EmailMessage message = new EmailMessage().setSenderAddress(sender).setToRecipients(ownerEmail)
                        .setSubject(subject).setBodyPlainText(text);
                // The send is accepted by the service here. The gateway does not wait for the delivery.
                client.beginSend(message);
                audit.record("gateway", "mail.sent", subject, null);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "mail send failed: {0}", e.getClass().getSimpleName());
                audit.record("gateway", "mail.failed", subject, null);
            }
        };
    }
}
