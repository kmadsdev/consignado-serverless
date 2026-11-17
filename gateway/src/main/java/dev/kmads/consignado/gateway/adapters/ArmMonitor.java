package dev.kmads.consignado.gateway.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.core.Logs;
import dev.kmads.consignado.gateway.ports.AzureMonitor;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plain HTTPS GET calls to ARM with the managed identity (role Monitoring Reader on rg-consignado):
 * Azure Monitor metrics, resource health and the resource itself. 3 s per call. Never throws.
 */
public final class ArmMonitor implements AzureMonitor {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    private static final String ARM = "https://management.azure.com";
    static final String METRICS_API = "2023-10-01";
    static final String HEALTH_API = "2020-05-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final AzureTokens tokens;

    public ArmMonitor(AzureTokens tokens) {
        this.tokens = tokens;
    }

    static String metricsUrl(String resourceId, List<String> names) {
        return ARM + resourceId + "/providers/Microsoft.Insights/metrics?api-version=" + METRICS_API
                + "&metricnames=" + URLEncoder.encode(String.join(",", names), StandardCharsets.UTF_8)
                + "&timespan=PT5M&interval=PT1M&aggregation=Average,Total,Maximum";
    }

    static String requestMetricsUrl(String appInsightsId) {
        return ARM + appInsightsId + "/providers/Microsoft.Insights/metrics?api-version=" + METRICS_API
                + "&metricnames=" + URLEncoder.encode("requests/count,requests/failed,requests/duration",
                        StandardCharsets.UTF_8)
                + "&timespan=PT5M&interval=PT1M&aggregation=Average,Count"
                + "&$filter=" + URLEncoder.encode("cloud/roleName eq '*'", StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String healthUrl(String resourceId) {
        return ARM + resourceId + "/providers/Microsoft.ResourceHealth/availabilityStatuses/current?api-version="
                + HEALTH_API;
    }

    /** The api-version of a resource read, by resource type (versions checked with az rest on 2026-11-17). */
    static String resourceUrl(String resourceId) {
        String id = resourceId.toLowerCase(Locale.ROOT);
        String version = id.contains("/microsoft.web/sites/") ? "2023-12-01"
                : id.contains("/microsoft.keyvault/vaults/") ? "2023-07-01"
                : id.contains("/microsoft.dbforpostgresql/flexibleservers/") ? "2024-08-01"
                : id.contains("/microsoft.containerservice/managedclusters/") ? "2024-09-01"
                : "2021-04-01";
        return ARM + resourceId + "?api-version=" + version;
    }

    @Override
    public Optional<JsonNode> metrics(String resourceId, List<String> metricNames) {
        return get(metricsUrl(resourceId, metricNames), "metrics");
    }

    @Override
    public Optional<JsonNode> requestMetrics(String appInsightsId) {
        return get(requestMetricsUrl(appInsightsId), "request metrics");
    }

    @Override
    public Optional<JsonNode> health(String resourceId) {
        return get(healthUrl(resourceId), "resource health");
    }

    @Override
    public Optional<JsonNode> resource(String resourceId) {
        return get(resourceUrl(resourceId), "resource");
    }

    private Optional<JsonNode> get(String url, String what) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + tokens.token(AzureTokens.ARM_SCOPE))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                LOG.log(Level.WARNING, Logs.fmt("status: ARM {0}: HTTP {1}", what, response.statusCode()));
                return Optional.empty();
            }
            return Optional.of(Json.MAPPER.readTree(response.body()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, Logs.fmt("status: ARM {0} failed: {1}", what, e.getClass().getSimpleName()));
            return Optional.empty();
        }
    }
}
