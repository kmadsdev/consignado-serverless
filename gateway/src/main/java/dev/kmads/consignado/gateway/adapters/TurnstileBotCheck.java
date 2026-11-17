package dev.kmads.consignado.gateway.adapters;

import dev.kmads.consignado.gateway.core.Logs;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.ports.BotCheck;
import dev.kmads.consignado.gateway.ports.Secrets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Cloudflare Turnstile. No secret in Key Vault yet: accept and log. Cloudflare does not answer: refuse. */
public final class TurnstileBotCheck implements BotCheck {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    public static final String SECRET_NAME = "turnstile-secret";
    private static final URI VERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    private final Secrets secrets;

    public TurnstileBotCheck(Secrets secrets) {
        this.secrets = secrets;
    }

    @Override
    public boolean verify(String token, String clientIp) {
        Secrets.Read secret;
        try {
            secret = secrets.read(SECRET_NAME);
        } catch (RuntimeException e) {
            LOG.warning("bot check: the secret could not be read; request refused");
            return false;
        }
        if (!secret.found() || secret.value().isBlank()) {
            LOG.warning("bot check not configured");
            return true;
        }
        if (token == null || token.isBlank()) return false;
        try {
            String form = "secret=" + enc(secret.value()) + "&response=" + enc(token)
                    + (clientIp == null ? "" : "&remoteip=" + enc(clientIp));
            HttpRequest request = HttpRequest.newBuilder(VERIFY).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && Json.MAPPER.readTree(response.body()).path("success").asBoolean(false);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, Logs.fmt("bot check call failed: {0}", e.getClass().getSimpleName()));
            return false;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
