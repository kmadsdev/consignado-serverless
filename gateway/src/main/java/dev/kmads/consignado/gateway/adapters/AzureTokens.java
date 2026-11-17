package dev.kmads.consignado.gateway.adapters;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Managed identity tokens for the plain HTTPS calls (ARM, Kubernetes, Blob). Cached until 5 minutes before expiry. */
public final class AzureTokens {
    public static final String ARM_SCOPE = "https://management.azure.com/.default";
    public static final String STORAGE_SCOPE = "https://storage.azure.com/.default";
    /** The Entra ID application of the AKS API server (the same id in every tenant). */
    public static final String AKS_SCOPE = "6dae42f8-4368-4678-94ff-3960e28e3630/.default";

    private final TokenCredential credential;
    private final Map<String, AccessToken> cache = new ConcurrentHashMap<>();

    public AzureTokens(TokenCredential credential) {
        this.credential = credential;
    }

    public String token(String scope) {
        AccessToken token = cache.get(scope);
        if (token == null || token.getExpiresAt().isBefore(OffsetDateTime.now().plusMinutes(5))) {
            token = credential.getTokenSync(new TokenRequestContext().addScopes(scope));
            cache.put(scope, token);
        }
        return token.getToken();
    }
}
