package dev.kmads.consignado.gateway.adapters;

import dev.kmads.consignado.gateway.core.Logs;
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
            // Any HTTP answer proves that the API server is up, also a 403.
            JsonNode pods = get("/api/v1/namespaces/" + NAMESPACE + "/pods", token);
            JsonNode endpoints = get("/api/v1/namespaces/" + NAMESPACE + "/endpoints", token);
            // Nodes are cluster-scoped. The role "Azure Kubernetes Service RBAC Reader" cannot list them (403).
            // Then the node comes from the pods: a pod with a running container proves that its node is Ready.
            JsonNode nodes = get("/api/v1/nodes", token);
            List<ClusterView.Pod> podList = pods == null ? List.of() : KubeParser.pods(pods);
            List<ClusterView.Node> nodeList = nodes != null ? KubeParser.nodes(nodes) : KubeParser.nodesFromPods(podList);
            ClusterView view = new ClusterView(true, nodeList, podList,
                    endpoints == null ? 0 : KubeParser.readyEndpoints(endpoints));
            ClusterView.Pod best = view.bestPod();
            LOG.log(Level.INFO, Logs.fmt("Kubernetes view: nodes {0} (from {1}), pods {2}, best pod {3} ready {4}, ready endpoints {5}",
                    nodeList.size(), nodes != null ? "node list" : "pods", podList.size(),
                    best == null ? "none" : best.name(), best != null && best.ready(), view.readyEndpoints()));
            return view;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, Logs.fmt("Kubernetes API not reachable: {0}", Logs.of(e)));
            return ClusterView.DOWN;
        }
    }

    /** Role "Azure Kubernetes Service RBAC Reader" allows apps/deployments/read and autoscaling/hpa/read. */
    @Override
    public java.util.Optional<ClusterView.Workload> workload() {
        try {
            String token = tokens.token(AzureTokens.AKS_SCOPE);
            JsonNode deployments = get("/apis/apps/v1/namespaces/" + NAMESPACE + "/deployments", token);
            if (deployments == null) return java.util.Optional.empty();
            JsonNode hpas = get("/apis/autoscaling/v2/namespaces/" + NAMESPACE + "/horizontalpodautoscalers", token);
            return java.util.Optional.of(new ClusterView.Workload(KubeParser.deployments(deployments),
                    hpas == null ? List.of() : KubeParser.autoscalers(hpas)));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, Logs.fmt("Kubernetes workload read failed: {0}", Logs.of(e)));
            return java.util.Optional.empty();
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
            LOG.log(Level.WARNING, Logs.fmt("Kubernetes API GET {0}: HTTP {1}", path, response.statusCode()));
            return null;
        }
        return Json.MAPPER.readTree(response.body());
    }
}
