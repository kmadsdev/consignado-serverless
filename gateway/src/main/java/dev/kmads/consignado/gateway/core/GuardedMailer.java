package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.Mailer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps a real mail sender. A failed mail never fails the request of the user: it leaves one log line
 * and one audit row. A sent mail also leaves one log line and one audit row, so Application Insights
 * shows every send. The subject is a fixed text; the body and the addresses are never logged.
 */
public final class GuardedMailer implements Mailer {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");

    /** The real send. It may throw. */
    @FunctionalInterface
    public interface Sender {
        void send(String subject, String text) throws Exception;
    }

    private final Sender sender;
    private final AuditLog audit;

    public GuardedMailer(Sender sender, AuditLog audit) {
        this.sender = sender;
        this.audit = audit;
    }

    @Override
    public void sendToOwner(String subject, String text) {
        try {
            sender.send(subject, text);
        } catch (Exception | LinkageError e) {
            LOG.log(Level.WARNING, Logs.fmt("mail to the owner failed: {0}: {1}", subject, noAddress(Logs.of(e))));
            audit.record("gateway", "mail.failed", subject, null);
            return;
        }
        LOG.log(Level.INFO, Logs.fmt("mail to the owner accepted by ACS: {0}", subject));
        audit.record("gateway", "mail.sent", subject, null);
    }

    /** An error text of the mail service can contain an address: it is removed before the log. */
    static String noAddress(String text) {
        return text.replaceAll("[^\\s@<>\"',;:]+@[^\\s@<>\"',;:]+", "<address>");
    }
}
