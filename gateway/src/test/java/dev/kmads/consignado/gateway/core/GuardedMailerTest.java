package dev.kmads.consignado.gateway.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardedMailerTest {
    private final List<String> audit = new ArrayList<>();

    @Test
    void aSentMailWritesOneAuditRow() {
        List<String> sent = new ArrayList<>();
        new GuardedMailer((s, t) -> sent.add(s), (who, action, target, ip) -> audit.add(action + ":" + target))
                .sendToOwner("Consignado: new user", "text");
        assertEquals(List.of("Consignado: new user"), sent);
        assertEquals(List.of("mail.sent:Consignado: new user"), audit);
    }

    @Test
    void aFailedMailNeverThrowsAndWritesOneAuditRow() {
        GuardedMailer mailer = new GuardedMailer((s, t) -> { throw new java.io.IOException("refused"); },
                (who, action, target, ip) -> audit.add(action));
        assertDoesNotThrow(() -> mailer.sendToOwner("Consignado: new user", "text"));
        GuardedMailer linkage = new GuardedMailer((s, t) -> { throw new NoClassDefFoundError("x"); },
                (who, action, target, ip) -> audit.add(action));
        assertDoesNotThrow(() -> linkage.sendToOwner("Consignado: new user", "text"));
        assertEquals(List.of("mail.failed", "mail.failed"), audit);
    }

    @Test
    void theLogTextHasNoAddress() {
        String text = GuardedMailer.noAddress("400 sender DoNotReply@abc.azurecomm.net to owner@example.com refused");
        assertFalse(text.contains("@"));
        assertTrue(text.contains("<address>"));
    }
}
