package dev.kmads.consignado.gateway.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.core.PowerState;
import dev.kmads.consignado.gateway.ports.ClusterControl;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The cluster through Azure Resource Manager, with plain HTTPS. This class can do exactly three calls:
 * GET the cluster, POST start, POST stop. It has no code for DELETE, PUT or PATCH.
 */
public final class ArmClusterControl implements ClusterControl {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    private static final String API_VERSION = "2024-09-01";
    private static final String ARM = "https://management.azure.com";

    private final AzureTokens tokens;
    private final String resourceId;

    public ArmClusterControl(AzureTokens tokens, String aksResourceId) {
        this.tokens = tokens;
        this.resourceId = aksResourceId;
    }

    @Override
    public PowerState powerState() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ARM + resourceId + "?api-version=" + API_VERSION))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + tokens.token(AzureTokens.ARM_SCOPE))
                    .GET().build();
            HttpResponse<String> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.log(Level.WARNING, "ARM get cluster: HTTP {0}", response.statusCode());
                return PowerState.UNKNOWN;
            }
            return parse(response.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "ARM get cluster failed: {0}", e.getClass().getSimpleName());
            return PowerState.UNKNOWN;
        }
    }

    static PowerState parse(String body) throws Exception {
        JsonNode properties = Json.MAPPER.readTree(body).path("properties");
        String code = properties.path("powerState").path("code").asText("Unknown");
        String provisioning = properties.path("provisioningState").asText("Unknown");
        return new PowerState(code, provisioning);
    }

    @Override
    public boolean start() {
        return post("start");
    }

    @Override
    public boolean stop() {
        return post("stop");
    }

    private boolean post(String action) {
        try {
            HttpRequest request = HttpRequest
                    .newBuilder(URI.create(ARM + resourceId + "/" + action + "?api-version=" + API_VERSION))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + tokens.token(AzureTokens.ARM_SCOPE))
                    .header("Content-Length", "0")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            LOG.log(Level.INFO, "ARM {0} cluster: HTTP {1}", new Object[] {action, status});
            return status == 200 || status == 202 || status == 204;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "ARM {0} cluster failed: {1}", new Object[] {action, e.getClass().getSimpleName()});
            return false;
        }
    }
}
