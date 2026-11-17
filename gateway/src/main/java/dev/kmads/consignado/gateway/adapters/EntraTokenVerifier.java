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

    public EntraTokenVerifier(String tenantId, String audience, JWKSource<SecurityContext> keys, String clientApp) {
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
            JWKSource<SecurityContext> keys = JWKSourceBuilder
                    .create(URI.create("https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys").toURL())
                    .cache(60 * 60 * 1000L, 15 * 1000L)
                    .build();
            return new EntraTokenVerifier(tenantId, audience, keys, clientApp);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("Invalid environment variable: ENTRA_TENANT_ID");
        }
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
