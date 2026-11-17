package dev.kmads.consignado.gateway.core;

/** The token was refused. The reason is for the log only, never for the response. */
public final class AuthException extends Exception {
    public AuthException(String reason) {
        super(reason);
    }
}
