package dev.kmads.consignado.gateway.adapters;

import dev.kmads.consignado.gateway.core.ApiTiming;
import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.core.Logs;
import java.util.logging.Level;
import dev.kmads.consignado.gateway.ports.Upstream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** The HTTP client for the two API hosts. Connect 5 s; total 30 s for Functions (cold start), 10 s for AKS. */
public final class HttpUpstream implements Upstream {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("consignado.gateway");

    private final String functionsBaseUrl;
    private final String aksBaseUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Http.CONNECT)
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpUpstream(String functionsBaseUrl, String aksBaseUrl) {
        this.functionsBaseUrl = functionsBaseUrl;
        this.aksBaseUrl = aksBaseUrl;
    }

    @Override
    public Response call(Request request) throws UpstreamException {
        boolean functions = request.host() == HostKind.FUNCTIONS;
        String url = (functions ? functionsBaseUrl : aksBaseUrl) + "/" + request.path()
                + (request.query() == null ? "" : "?" + request.query());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(request.host().upstreamTimeout());
            request.headers().forEach(builder::header);
            HttpRequest.BodyPublisher body = request.body() == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.body());
            builder.method(request.method(), body);
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(),
                    response.headers().firstValue("content-type").orElse(null), response.body(),
                    response.headers().firstValue(ApiTiming.HEADER).orElse(null),
                    response.headers().firstValue("etag").orElse(null),
                    locationPath(response.headers().firstValue("location").orElse(null),
                            functions ? functionsBaseUrl : aksBaseUrl));
        } catch (HttpTimeoutException e) {
            LOG.log(Level.WARNING, Logs.fmt("upstream {0} {1} timeout: {2}", request.host().id(), request.path(), Logs.of(e)));
            throw new UpstreamException(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(false);
        } catch (Exception e) {
            LOG.log(Level.WARNING, Logs.fmt("upstream {0} {1} failed: {2}", request.host().id(), request.path(), Logs.of(e)));
            throw new UpstreamException(false);
        }
    }

    /**
     * The path of a Location header, or null. A relative reference ("/api/v1/...") is kept. An absolute URL is
     * kept only when its host is the host of the API base URL that was called (the scheme may differ: an
     * ingress can end TLS); its path is returned, and a base path prefix of the base URL is removed.
     * Any other value (another host, a malformed URL) is null: the gateway never sends a foreign URL on.
     */
    static String locationPath(String location, String baseUrl) {
        if (location == null || location.isBlank() || baseUrl == null) return null;
        try {
            URI loc = URI.create(location.strip());
            URI base = URI.create(baseUrl);
            String path = loc.getRawPath();
            if (path == null || path.isEmpty()) return null;
            if (loc.isAbsolute() || loc.getRawAuthority() != null) {
                if (loc.getHost() == null || base.getHost() == null
                        || !loc.getHost().equalsIgnoreCase(base.getHost())) {
                    return null;
                }
                String prefix = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
                if (!prefix.isEmpty()) {
                    if (!path.startsWith(prefix + "/")) return null;
                    path = path.substring(prefix.length());
                }
            }
            return path.startsWith("/") ? path : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
