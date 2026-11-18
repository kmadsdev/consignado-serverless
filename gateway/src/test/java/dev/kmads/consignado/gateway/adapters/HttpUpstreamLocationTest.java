package dev.kmads.consignado.gateway.adapters;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** M2: a Location of the API host becomes a path; a Location on another origin is dropped. */
class HttpUpstreamLocationTest {
    private static final String FN = "https://apifn-consignado.azurewebsites.net";

    @Test
    void anAbsoluteLocationOnTheCalledHostBecomesItsPath() {
        assertEquals("/api/v1/contracts/c9",
                HttpUpstream.locationPath(FN + "/api/v1/contracts/c9", FN));
        assertEquals("/api/v1/contracts/c9", HttpUpstream.locationPath(
                "http://APIFN-consignado.azurewebsites.net/api/v1/contracts/c9", FN), "an ingress can end TLS");
    }

    @Test
    void aRelativeLocationIsKept() {
        assertEquals("/api/v1/contracts/c9", HttpUpstream.locationPath("/api/v1/contracts/c9", FN));
    }

    @Test
    void theBasePathOfTheHostIsRemoved() {
        String aks = "http://10.0.0.4/consignado";
        assertEquals("/api/v1/contracts/c9", HttpUpstream.locationPath("http://10.0.0.4/consignado/api/v1/contracts/c9", aks));
        assertNull(HttpUpstream.locationPath("http://10.0.0.4/other/api/v1/contracts/c9", aks));
    }

    @Test
    void anotherHostOrAMalformedValueIsNull() {
        assertNull(HttpUpstream.locationPath("https://evil.example/api/v1/contracts/c9", FN));
        assertNull(HttpUpstream.locationPath("//evil.example/api/v1/contracts/c9", FN));
        assertNull(HttpUpstream.locationPath("http://[bad", FN));
        assertNull(HttpUpstream.locationPath("api/v1/contracts/c9", FN));
        assertNull(HttpUpstream.locationPath("", FN));
        assertNull(HttpUpstream.locationPath(null, FN));
    }
}
