package dev.kmads.consignado.gateway.ports;

public interface BotCheck {
    /** True when the Turnstile token is good (or when the bot check is not configured yet). */
    boolean verify(String token, String clientIp);
}
