package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 of IP addresses and device fingerprints. The raw values are never stored. */
public final class Hasher {
    public static final String KEY_NAME = "hash-key";

    private final Secrets secrets;
    private volatile byte[] key;

    public Hasher(Secrets secrets) {
        this.secrets = secrets;
    }

    public String hash(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC is not available");
        }
    }

    private byte[] key() {
        byte[] k = key;
        if (k == null) {
            synchronized (this) {
                if (key == null) {
                    // The gateway only reads Key Vault. Terraform makes the key. No key: fail, never a default.
                    String value = secrets.read(KEY_NAME).value();
                    if (value == null || value.isBlank()) {
                        throw new IllegalStateException("Missing Key Vault secret: " + KEY_NAME);
                    }
                    key = value.getBytes(StandardCharsets.UTF_8);
                }
                k = key;
            }
        }
        return k;
    }
}
