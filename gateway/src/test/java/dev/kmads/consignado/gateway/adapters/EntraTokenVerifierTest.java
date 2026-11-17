package dev.kmads.consignado.gateway.adapters;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import dev.kmads.consignado.gateway.core.AuthException;
import dev.kmads.consignado.gateway.core.Claims;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tokens signed with a local RSA key, verified against a local JWKS. No network. */
class EntraTokenVerifierTest {
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String AUDIENCE = "22222222-2222-2222-2222-222222222222";
    private static final String OID = "33333333-3333-3333-3333-333333333333";
    private static RSAKey key;
    private static RSAKey otherKey;
    private static EntraTokenVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        verifier = new EntraTokenVerifier(TENANT, AUDIENCE, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));
    }

    private static String token(RSAKey signer, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://login.microsoftonline.com/" + TENANT + "/v2.0")
                .audience(AUDIENCE)
                .claim("tid", TENANT)
                .claim("oid", OID)
                .claim("scp", "access_as_user")
                .claim("preferred_username", "ana@example.com")
                .claim("name", "Ana Souza")
                .notBeforeTime(new Date(now - 10_000))
                .expirationTime(new Date(now + 600_000));
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    private static void refused(String token) {
        assertThrows(AuthException.class, () -> verifier.verify(token));
    }

    @Test
    void goodTokenGivesTheClaims() throws Exception {
        Claims c = verifier.verify(token(key, b -> { }));
        assertEquals(OID, c.oid());
        assertEquals("ana@example.com", c.email());
        assertEquals("Ana Souza", c.name());
        assertTrue(c.scopes().contains("access_as_user"));
    }

    @Test
    void audienceInApiUriFormIsTheSameApplication() throws Exception {
        assertEquals(OID, verifier.verify(token(key, b -> b.audience("api://" + AUDIENCE))).oid());
    }

    @Test
    void wrongSignatureIsRefused() throws Exception {
        refused(token(otherKey, b -> { }));
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        refused(token(key, b -> b.expirationTime(new Date(System.currentTimeMillis() - 300_000))));
    }

    @Test
    void tokenThatIsNotValidYetIsRefused() throws Exception {
        refused(token(key, b -> b.notBeforeTime(new Date(System.currentTimeMillis() + 300_000))));
    }

    @Test
    void wrongAudienceIsRefused() throws Exception {
        refused(token(key, b -> b.audience("44444444-4444-4444-4444-444444444444")));
    }

    @Test
    void wrongIssuerOrTenantIsRefused() throws Exception {
        refused(token(key, b -> b.issuer("https://login.microsoftonline.com/other/v2.0")));
        refused(token(key, b -> b.issuer("https://sts.windows.net/" + TENANT + "/")));
        refused(token(key, b -> b.claim("tid", "55555555-5555-5555-5555-555555555555")));
    }

    @Test
    void missingScopeIsRefused() throws Exception {
        refused(token(key, b -> b.claim("scp", "other.read")));
        refused(token(key, b -> b.claim("scp", null).claim("roles", java.util.List.of("access_as_user"))));
    }

    @Test
    void missingOidOrExpIsRefused() throws Exception {
        refused(token(key, b -> b.claim("oid", null)));
        refused(token(key, b -> b.expirationTime(null)));
    }

    @Test
    void unsignedAndBrokenTokensAreRefused() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://login.microsoftonline.com/" + TENANT + "/v2.0").audience(AUDIENCE)
                .claim("tid", TENANT).claim("oid", OID).claim("scp", "access_as_user")
                .expirationTime(new Date(System.currentTimeMillis() + 600_000)).build();
        refused(new PlainJWT(claims).serialize());
        refused("not-a-token");
        refused("");
        refused(null);
    }
}
