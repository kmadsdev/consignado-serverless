package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.AuthException;
import dev.kmads.consignado.gateway.core.Claims;

public interface TokenVerifier {
    /** Verifies a raw bearer token (no "Bearer " prefix). */
    Claims verify(String token) throws AuthException;
}
