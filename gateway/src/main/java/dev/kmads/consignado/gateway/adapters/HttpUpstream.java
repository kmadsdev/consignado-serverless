package dev.kmads.consignado.gateway.adapters;

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
    private static final Duration FUNCTIONS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AKS_TIMEOUT = Duration.ofSeconds(10);

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
                    .timeout(functions ? FUNCTIONS_TIMEOUT : AKS_TIMEOUT);
            request.headers().forEach(builder::header);
            HttpRequest.BodyPublisher body = request.body() == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.body());
            builder.method(request.method(), body);
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(),
                    response.headers().firstValue("content-type").orElse(null), response.body());
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
}
