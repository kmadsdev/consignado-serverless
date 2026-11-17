package dev.kmads.consignado.gateway.core;

import dev.kmads.consignado.gateway.ports.Secrets;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
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
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(503, "unavailable", "Service is not available now");
        }
    }

    private byte[] key() {
        byte[] k = key;
        if (k == null) {
            synchronized (this) {
                if (key == null) {
                    String value;
                    try {
                        value = secrets.readOrCreate(KEY_NAME, Hasher::randomKey);
                    } catch (RuntimeException e) {
                        throw new ApiException(503, "unavailable", "Service is not available now");
                    }
                    key = value.getBytes(StandardCharsets.UTF_8);
                }
                k = key;
            }
        }
        return k;
    }

    static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
