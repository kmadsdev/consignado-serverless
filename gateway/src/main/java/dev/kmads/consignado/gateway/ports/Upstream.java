package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.HostKind;
import java.util.Map;

/** One HTTP call to an API host. The adapter knows the base URL and the timeouts of each host. */
public interface Upstream {
    record Request(HostKind host, String method, String path, String query, Map<String, String> headers,
                   byte[] body) {}

    /**
     * timing: the raw response header X-Consignado-Timing of the API host, or null. It is the only upstream
     * response header besides Content-Type that the gateway reads; none is passed on to the browser.
     */
    record Response(int status, String contentType, byte[] body, String timing) {
        public Response(int status, String contentType, byte[] body) {
            this(status, contentType, body, null);
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
