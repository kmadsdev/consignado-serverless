package dev.kmads.consignado.gateway.adapters;

import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import dev.kmads.consignado.gateway.ports.Secrets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Key Vault secrets, read-only, cached 5 minutes in memory. A missing secret is cached 1 minute. */
public final class KeyVaultSecrets implements Secrets {
    private static final long TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final long MISSING_TTL_MS = Duration.ofMinutes(1).toMillis();

    private record Entry(String value, long expiresAt) {}

    private final SecretClient client;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public KeyVaultSecrets(String vaultUrl, TokenCredential credential) {
        this.client = new SecretClientBuilder().vaultUrl(vaultUrl).credential(credential).buildClient();
    }

    @Override
    public Read read(String name) {
        long now = System.currentTimeMillis();
        Entry entry = cache.get(name);
        if (entry != null && entry.expiresAt() > now) return new Read(entry.value(), true);
        String value;
        try {
            value = client.getSecret(name).getValue();
        } catch (ResourceNotFoundException e) {
            value = null;
        }
        cache.put(name, new Entry(value, now + (value == null ? MISSING_TTL_MS : TTL_MS)));
        return new Read(value, false);
    }
}
