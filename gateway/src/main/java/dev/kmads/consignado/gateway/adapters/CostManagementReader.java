package dev.kmads.consignado.gateway.adapters;

import dev.kmads.consignado.gateway.core.Logs;
import com.fasterxml.jackson.databind.JsonNode;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.ports.CostReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Month-to-date cost of the resource group of the cluster, from the Cost Management query API. Cached 30 minutes.
 * The aggregation column is "Cost" (the billing currency, BRL on this subscription). "CostUSD" is the same cost
 * converted to US dollars, but the Currency column still names the billing currency: comparing it with the budget
 * (which Azure keeps in the billing currency) was the defect of 2026-11-18.
 */
public final class CostManagementReader implements CostReader {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(5);
    private static final String BODY = "{\"type\":\"ActualCost\",\"timeframe\":\"MonthToDate\",\"dataset\":"
            + "{\"granularity\":\"None\",\"aggregation\":{\"totalCost\":{\"name\":\"Cost\",\"function\":\"Sum\"}}}}";

    private final AzureTokens tokens;
    private final URI uri;
    private volatile Cost cached;
    private volatile Instant nextTry = Instant.EPOCH;

    /** aksResourceId gives the subscription and the resource group: /subscriptions/{id}/resourceGroups/{rg}/... */
    public CostManagementReader(AzureTokens tokens, String aksResourceId) {
        this.tokens = tokens;
        String[] parts = aksResourceId.split("/");
        this.uri = URI.create("https://management.azure.com/subscriptions/" + parts[2] + "/resourceGroups/" + parts[4]
                + "/providers/Microsoft.CostManagement/query?api-version=2023-11-01");
    }

    @Override
    public Optional<Cost> monthToDate() {
        Instant now = Instant.now();
        if (now.isBefore(nextTry)) return Optional.ofNullable(cached);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + tokens.token(AzureTokens.ARM_SCOPE))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
            HttpResponse<String> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            cached = new Cost(parse(response.body()), currency(response.body()), now);
            nextTry = now.plus(TTL);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, Logs.fmt("cost query failed: {0}", e.getClass().getSimpleName()));
            nextTry = now.plus(FAILURE_TTL);
        }
        return Optional.ofNullable(cached);
    }

    /** The first numeric cell of the first row. No row means no cost yet in this month. */
    static double parse(String body) throws Exception {
        JsonNode rows = Json.MAPPER.readTree(body).path("properties").path("rows");
        if (rows.isEmpty()) return 0.0;
        for (JsonNode cell : rows.get(0)) {
            if (cell.isNumber()) return Math.round(cell.asDouble() * 100.0) / 100.0;
        }
        throw new IllegalStateException("no cost column");
    }

    /** The value of the "Currency" column in the first row (upper case), or null when there is none. */
    static String currency(String body) throws Exception {
        JsonNode props = Json.MAPPER.readTree(body).path("properties");
        JsonNode columns = props.path("columns");
        JsonNode rows = props.path("rows");
        for (int i = 0; i < columns.size(); i++) {
            if (!"Currency".equalsIgnoreCase(columns.get(i).path("name").asText())) continue;
            if (rows.isEmpty()) return null;
            String v = rows.get(0).path(i).asText("");
            return v.matches("[A-Za-z]{3}") ? v.toUpperCase(java.util.Locale.ROOT) : null;
        }
        return null;
    }
}
