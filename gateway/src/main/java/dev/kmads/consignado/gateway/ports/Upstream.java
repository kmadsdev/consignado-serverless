package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.HostKind;
import java.util.Map;

/** One HTTP call to an API host. The adapter knows the base URL and the timeouts of each host. */
public interface Upstream {
    record Request(HostKind host, String method, String path, String query, Map<String, String> headers,
                   byte[] body) {}

    record Response(int status, String contentType, byte[] body) {}

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
