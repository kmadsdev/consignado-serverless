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

/**
 * Cloudflare Turnstile. Fails closed: no secret, a Key Vault error, or no answer from Cloudflare = refuse.
 * The hostname of the answer must be one of the site hosts (TURNSTILE_HOSTNAMES).
 */
public final class TurnstileBotCheck implements BotCheck {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    public static final String SECRET_NAME = "turnstile-secret";
    private static final URI VERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    private final Secrets secrets;
    private final java.util.Set<String> hostnames;

    public TurnstileBotCheck(Secrets secrets, java.util.Collection<String> hostnames) {
        this.secrets = secrets;
        this.hostnames = java.util.Set.copyOf(hostnames);
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
            LOG.warning("bot check not configured: request refused (secret turnstile-secret has no value)");
            return false;
        }
        if (token == null || token.isBlank()) return false;
        try {
            String form = "secret=" + enc(secret.value()) + "&response=" + enc(token)
                    + (clientIp == null ? "" : "&remoteip=" + enc(clientIp));
            HttpRequest request = HttpRequest.newBuilder(VERIFY).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && accepted(response.body(), hostnames);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, Logs.fmt("bot check call failed: {0}", e.getClass().getSimpleName()));
            return false;
        }
    }

    /** success must be true and hostname must be one of the site hosts. */
    static boolean accepted(String body, java.util.Set<String> hostnames) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = Json.MAPPER.readTree(body);
            if (!n.path("success").asBoolean(false)) return false;
            String host = n.path("hostname").asText("");
            if (!hostnames.contains(host)) {
                LOG.warning(Logs.fmt("bot check: token from another hostname refused: {0}",
                        host.length() > 80 ? host.substring(0, 80) : host));
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
