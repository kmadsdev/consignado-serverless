package dev.kmads.consignado.gateway.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.ClusterView;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.ports.ClusterObserver;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Read-only view of namespace consignado through the Kubernetes API, with plain HTTPS GET calls.
 * The TLS trust store holds only the cluster CA from AKS_CA_CERT_B64.
 */
public final class KubeClusterObserver implements ClusterObserver {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    private static final String NAMESPACE = "consignado";

    private final AzureTokens tokens;
    private final String apiServerUrl;
    private final HttpClient client;

    public KubeClusterObserver(AzureTokens tokens, String apiServerUrl, String caCertB64) {
        this.tokens = tokens;
        this.apiServerUrl = apiServerUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Http.CONNECT).sslContext(trustOnly(caCertB64))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static SSLContext trustOnly(String caCertB64) {
        try {
            byte[] pem = Base64.getMimeDecoder().decode(caCertB64);
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem))) {
                store.setCertificateEntry("aks-ca-" + i++, cert);
            }
            if (i == 0) throw new IllegalStateException("no certificate");
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid environment variable: AKS_CA_CERT_B64");
        }
    }

    @Override
    public ClusterView observe() {
        try {
            String token = tokens.token(AzureTokens.AKS_SCOPE);
            JsonNode nodes = get("/api/v1/nodes", token);
            if (nodes == null) return ClusterView.DOWN;
            JsonNode pods = get("/api/v1/namespaces/" + NAMESPACE + "/pods", token);
            JsonNode endpoints = get("/api/v1/namespaces/" + NAMESPACE + "/endpoints", token);
            return new ClusterView(true, KubeParser.nodes(nodes),
                    pods == null ? List.of() : KubeParser.pods(pods),
                    endpoints == null ? 0 : KubeParser.readyEndpoints(endpoints));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.INFO, "Kubernetes API not reachable: {0}", e.getClass().getSimpleName());
            return ClusterView.DOWN;
        }
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiServerUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            LOG.log(Level.INFO, "Kubernetes API GET: HTTP {0}", response.statusCode());
            return null;
        }
        return Json.MAPPER.readTree(response.body());
    }
}
