package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.HostKind;
import java.util.Map;

/** One HTTP call to an API host. The adapter knows the base URL and the timeouts of each host. */
public interface Upstream {
    record Request(HostKind host, String method, String path, String query, Map<String, String> headers,
                   byte[] body) {}

    /**
     * timing: the raw response header X-Consignado-Timing of the API host, or null.
     * etag: the raw ETag response header, or null.
     * location: the Location response header as a path ("/api/v1/..."), or null. The adapter removes the
     * origin when it is the origin of the API host it called; a Location on another origin becomes null.
     * These are the only upstream response headers besides Content-Type that the gateway reads. ProxyService
     * decides which of them go on to the browser.
     */
    record Response(int status, String contentType, byte[] body, String timing, String etag, String location) {
        public Response(int status, String contentType, byte[] body) {
            this(status, contentType, body, null, null, null);
        }

        public Response(int status, String contentType, byte[] body, String timing) {
            this(status, contentType, body, timing, null, null);
        }
    }

    final class UpstreamException extends Exception {
        private final boolean timeout;

        public UpstreamException(boolean timeout) {
            super(timeout ? "upstream timeout" : "upstream failure");
            this.timeout = timeout;
        }

        public boolean timeout() { return timeout; }
    }

    Response call(Request request) throws UpstreamException;
}
