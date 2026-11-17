package dev.kmads.consignado.gateway.ports;

public interface Mailer {
    /** Sends a plain text email to the owner. Never throws. */
    void sendToOwner(String subject, String text);
}
