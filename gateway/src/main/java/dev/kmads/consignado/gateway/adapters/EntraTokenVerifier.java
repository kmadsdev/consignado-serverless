package dev.kmads.consignado.gateway.adapters;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.kmads.consignado.gateway.core.AuthException;
import dev.kmads.consignado.gateway.core.Claims;
import dev.kmads.consignado.gateway.ports.TokenVerifier;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Verifies an Entra ID v2 access token: RS256 signature against the tenant JWKS, issuer, audience,
 * tenant, exp and nbf (60 s clock skew), and the delegated scope access_as_user.
 */
public final class EntraTokenVerifier implements TokenVerifier {
    public static final String REQUIRED_SCOPE = "access_as_user";

    private final DefaultJWTProcessor<SecurityContext> processor;

    /** Client application that must have asked for the token (claim azp). Null = not pinned. */
    private final String clientApp;

    public EntraTokenVerifier(String tenantId, String audience, JWKSource<SecurityContext> keys) {
        this(tenantId, audience, keys, null);
    }

    private final JWKSource<SecurityContext> keySource;
    /** Set when the key set was really downloaded (not served from the cache). */
    private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> keysFetchedAt;

    public EntraTokenVerifier(String tenantId, String audience, JWKSource<SecurityContext> keys, String clientApp) {
        this(tenantId, audience, keys, clientApp, new java.util.concurrent.atomic.AtomicReference<>());
    }

    private EntraTokenVerifier(String tenantId, String audience, JWKSource<SecurityContext> keys, String clientApp,
                               java.util.concurrent.atomic.AtomicReference<java.time.Instant> fetchedAt) {
        this.keySource = keys;
        this.keysFetchedAt = fetchedAt;
        this.clientApp = clientApp;
        Set<String> audiences = new LinkedHashSet<>();
        audiences.add(audience);
        // The same application has two names: its client id and api://<client id>.
        audiences.add(audience.startsWith("api://") ? audience.substring("api://".length()) : "api://" + audience);
        JWTClaimsSet exact = new JWTClaimsSet.Builder()
                .issuer("https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("tid", tenantId)
                .build();
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        processor.setJWTClaimsSetVerifier(
                new DefaultJWTClaimsVerifier<>(audiences, exact, Set.of("exp", "oid"), null));
    }

    /** The production verifier: keys from the tenant JWKS endpoint, cached for 1 hour. */
    public static EntraTokenVerifier forTenant(String tenantId, String audience) {
        return forTenant(tenantId, audience, null);
    }

    /** clientApp: the SPA client id (ENTRA_SPA_CLIENT_ID); when set, a token for another client app is refused. */
    public static EntraTokenVerifier forTenant(String tenantId, String audience, String clientApp) {
        try {
            java.util.concurrent.atomic.AtomicReference<java.time.Instant> fetchedAt =
                    new java.util.concurrent.atomic.AtomicReference<>();
            com.nimbusds.jose.jwk.source.JWKSetSource<SecurityContext> download =
                    new com.nimbusds.jose.jwk.source.URLBasedJWKSetSource<>(
                            URI.create("https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys").toURL(),
                            new com.nimbusds.jose.util.DefaultResourceRetriever(
                                    JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT,
                                    JWKSourceBuilder.DEFAULT_HTTP_READ_TIMEOUT,
                                    JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT));
            // Records the time of each real download; the cache below serves the other calls.
            com.nimbusds.jose.jwk.source.JWKSetSource<SecurityContext> recorded =
                    new com.nimbusds.jose.jwk.source.JWKSetSource<>() {
                        @Override
                        public com.nimbusds.jose.jwk.JWKSet getJWKSet(
                                com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator evaluator, long currentTime,
                                SecurityContext context) throws com.nimbusds.jose.KeySourceException {
                            com.nimbusds.jose.jwk.JWKSet set = download.getJWKSet(evaluator, currentTime, context);
                            fetchedAt.set(java.time.Instant.now());
                            return set;
                        }

                        @Override
                        public void close() throws java.io.IOException {
                            download.close();
                        }
                    };
            JWKSource<SecurityContext> keys = JWKSourceBuilder.create(recorded)
                    .cache(60 * 60 * 1000L, 15 * 1000L)
                    .build();
            return new EntraTokenVerifier(tenantId, audience, keys, clientApp, fetchedAt);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("Invalid environment variable: ENTRA_TENANT_ID");
        }
    }

    /**
     * For the status page: loads the signing keys when the cache is empty (one download), and returns when
     * they were downloaded. Null when this verifier has no download record (tests with fixed keys).
     */
    public java.time.Instant signingKeysFetchedAt() throws Exception {
        java.util.List<com.nimbusds.jose.jwk.JWK> found = keySource.get(
                new com.nimbusds.jose.jwk.JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null);
        if (found.isEmpty()) throw new IllegalStateException("no signing keys");
        return keysFetchedAt.get();
    }

    @Override
    public Claims verify(String token) throws AuthException {
        if (token == null || token.isBlank()) throw new AuthException("empty token");
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (Exception e) {
            throw new AuthException(e.getClass().getSimpleName());
        }
        try {
            String scp = claims.getStringClaim("scp");
            Set<String> scopes = scp == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(scp.trim().split("\\s+")));
            if (!scopes.contains(REQUIRED_SCOPE)) throw new AuthException("scope missing");
            if (clientApp != null && !clientApp.equals(claims.getStringClaim("azp"))) {
                throw new AuthException("client application not allowed");
            }
            String oid = claims.getStringClaim("oid");
            if (oid == null || !oid.matches("[A-Za-z0-9\\-]{1,64}")) throw new AuthException("oid not valid");
            String email = claims.getStringClaim("preferred_username");
            if (email == null) email = claims.getStringClaim("email");
            String name = claims.getStringClaim("name");
            return new Claims(oid, email == null ? "" : email, name == null ? "" : name, scopes);
        } catch (java.text.ParseException e) {
            throw new AuthException("claim type");
        }
    }
}
