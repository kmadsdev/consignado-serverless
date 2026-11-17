package dev.kmads.consignado.gateway.ports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * Read-only ARM calls for the status page, with the managed identity (role Monitoring Reader on the
 * resource group). Each call has a 3 s timeout. Never throws: empty when Azure did not answer 200.
 */
public interface AzureMonitor {
    /** Platform metrics, last 5 minutes, interval 1 minute, aggregations Average, Total and Maximum. */
    Optional<JsonNode> metrics(String resourceId, List<String> metricNames);

    /** Resource health: Microsoft.ResourceHealth/availabilityStatuses/current. */
    Optional<JsonNode> health(String resourceId);

    /** The resource itself (location, properties.state). */
    Optional<JsonNode> resource(String resourceId);
}
