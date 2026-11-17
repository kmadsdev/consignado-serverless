package dev.kmads.consignado.gateway.adapters;

import static org.junit.jupiter.api.Assertions.*;

import dev.kmads.consignado.gateway.ports.Secrets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L4: fail closed, and the hostname of the answer must be a site host. No network call is made. */
class TurnstileBotCheckTest {
    private static final Set<String> HOSTS = Set.of("consignado.kmads.dev", "cluster.consignado.kmads.dev");

    @Test
    void noSecretOrKeyVaultErrorRefuses() {
        Secrets none = name -> new Secrets.Read(null, false);
        Secrets blank = name -> new Secrets.Read(" ", false);
        Secrets down = name -> { throw new IllegalStateException("down"); };
        for (Secrets s : List.of(none, blank, down)) {
            assertFalse(new TurnstileBotCheck(s, HOSTS).verify("token", "203.0.113.1"));
        }
    }

    @Test
    void answerNeedsSuccessAndASiteHostname() {
        assertTrue(TurnstileBotCheck.accepted("{\"success\":true,\"hostname\":\"consignado.kmads.dev\"}", HOSTS));
        assertTrue(TurnstileBotCheck.accepted("{\"success\":true,\"hostname\":\"cluster.consignado.kmads.dev\"}", HOSTS));
        assertFalse(TurnstileBotCheck.accepted("{\"success\":true,\"hostname\":\"evil.example\"}", HOSTS));
        assertFalse(TurnstileBotCheck.accepted("{\"success\":true}", HOSTS));
        assertFalse(TurnstileBotCheck.accepted("{\"success\":false,\"hostname\":\"consignado.kmads.dev\"}", HOSTS));
        assertFalse(TurnstileBotCheck.accepted("not json", HOSTS));
    }
}
