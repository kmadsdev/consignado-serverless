package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Azure Monitor metrics that the status page shows, and how one 5-minute answer becomes one number.
 * Only names that exist on the real resources are here (checked 2026-11-17 with
 * az monitor metrics list-definitions). Flex Consumption function apps expose no request count, no HTTP 5xx
 * and no response time as platform metrics, so those are not shown for them (never an invented number).
 */
public final class StatusMetrics {
    public enum Agg { TOTAL, AVERAGE, LATEST_MAX }

    /** azureName -> one Metric of the answer. scale converts the Azure unit to the shown unit. */
    public record Def(String azureName, Agg agg, String key, String label, String unit, double scale) {}

    /** The table (also in the README). Key: service id of the status answer. */
    public static final Map<String, List<Def>> TABLE = new LinkedHashMap<>();
    static {
        List<Def> functionApp = List.of(
                new Def("OnDemandFunctionExecutionCount", Agg.TOTAL, "executions", "Function executions", "count", 1),
                new Def("InstanceCount", Agg.AVERAGE, "instances", "Instances", "count", 1),
                new Def("CpuPercentage", Agg.AVERAGE, "cpu", "CPU", "%", 1),
                new Def("MemoryWorkingSet", Agg.AVERAGE, "memory", "Memory working set", "MB", 1.0 / (1024 * 1024)));
        TABLE.put("gateway", functionApp);
        TABLE.put("functions", functionApp);
        TABLE.put("keyvault", List.of(
                new Def("ServiceApiHit", Agg.TOTAL, "requests", "Requests", "count", 1),
                new Def("ServiceApiLatency", Agg.AVERAGE, "latency-avg", "Average latency", "ms", 1),
                new Def("Availability", Agg.AVERAGE, "availability", "Availability", "%", 1)));
        TABLE.put("database", List.of(
                new Def("cpu_percent", Agg.AVERAGE, "cpu", "CPU", "%", 1),
                new Def("memory_percent", Agg.AVERAGE, "memory", "Memory", "%", 1),
                new Def("active_connections", Agg.AVERAGE, "connections", "Active connections", "count", 1)));
        TABLE.put("node", List.of(
                new Def("node_cpu_usage_percentage", Agg.AVERAGE, "cpu", "Node CPU", "%", 1),
                new Def("node_memory_working_set_percentage", Agg.AVERAGE, "memory", "Node memory", "%", 1)));
    }

    /** Read only for the state of the database (1 = alive), not shown as a metric. */
    public static final String DB_ALIVE = "is_db_alive";

    private StatusMetrics() {}

    public static List<String> names(String serviceId) {
        List<String> out = new ArrayList<>();
        for (Def d : TABLE.getOrDefault(serviceId, List.of())) out.add(d.azureName());
        if (serviceId.equals("database")) out.add(DB_ALIVE);
        return out;
    }

    /** The metrics of one service from one answer. A metric with no data point is value null. */
    public static List<Views.Metric> metrics(String serviceId, JsonNode answer) {
        List<Views.Metric> out = new ArrayList<>();
        for (Def d : TABLE.getOrDefault(serviceId, List.of())) {
            Double v = reduce(answer, d.azureName(), d.agg());
            out.add(new Views.Metric(d.key(), d.label(), v == null ? null : round(v * d.scale()), d.unit(),
                    "last 5 min"));
        }
        return out;
    }

    /**
     * TOTAL: sum of "total" of the points that have it. AVERAGE: mean of "average" of the points that have it.
     * LATEST_MAX: "maximum" of the newest point that has it. Null when no point has the value.
     */
    public static Double reduce(JsonNode answer, String azureName, Agg agg) {
        if (answer == null) return null;
        for (JsonNode v : answer.path("value")) {
            if (!azureName.equals(v.path("name").path("value").asText())) continue;
            double sum = 0;
            int n = 0;
            Double latest = null;
            for (JsonNode series : v.path("timeseries")) {
                for (JsonNode p : series.path("data")) {
                    String field = agg == Agg.TOTAL ? "total" : agg == Agg.AVERAGE ? "average" : "maximum";
                    JsonNode x = p.get(field);
                    if (x == null || !x.isNumber()) continue;
                    sum += x.asDouble();
                    n++;
                    latest = x.asDouble();
                }
            }
            if (n == 0) return null;
            return switch (agg) {
                case TOTAL -> sum;
                case AVERAGE -> sum / n;
                case LATEST_MAX -> latest;
            };
        }
        return null;
    }

    /**
     * The request metrics of one function app from the Application Insights answer (split by cloud/roleName):
     * requests = sum of Count; errors = sum of Count of requests/failed (Azure sends no series for a role
     * with no failed request: 0 when the metric is in the answer, null when it is not); latency-avg = mean of the
     * minute averages weighted by their counts. requests/duration offers only Average, Minimum and Maximum,
     * so there is no p95.
     */
    public static List<Views.Metric> requestMetrics(JsonNode answer, String roleName) {
        Double count = null;
        Double failed = null;
        double weighted = 0;
        double weight = 0;
        for (JsonNode v : answer.path("value")) {
            String name = v.path("name").path("value").asText();
            JsonNode series = seriesOf(v, roleName);
            switch (name) {
                case "requests/count" -> count = series == null ? null : sum(series, "count");
                case "requests/failed" -> {
                    Double f = series == null ? null : sum(series, "count");
                    failed = f != null ? f : 0.0;
                }
                case "requests/duration" -> {
                    if (series != null) {
                        for (JsonNode p : series.path("data")) {
                            JsonNode a = p.get("average");
                            JsonNode c = p.get("count");
                            if (a == null || !a.isNumber() || c == null || !c.isNumber() || c.asDouble() <= 0) continue;
                            weighted += a.asDouble() * c.asDouble();
                            weight += c.asDouble();
                        }
                    }
                }
                default -> { }
            }
        }
        Double latency = weight > 0 ? round(weighted / weight) : null;
        return List.of(
                new Views.Metric("requests", "Requests", count, "count", "last 5 min"),
                new Views.Metric("errors", "Failed requests", failed, "count", "last 5 min"),
                new Views.Metric("latency-avg", "Average response time", latency, "ms", "last 5 min"));
    }

    /** The series of one cloud/roleName (Azure returns the dimension name in lower case). */
    private static JsonNode seriesOf(JsonNode metric, String roleName) {
        for (JsonNode ts : metric.path("timeseries")) {
            for (JsonNode m : ts.path("metadatavalues")) {
                if (m.path("name").path("value").asText().equalsIgnoreCase("cloud/roleName")
                        && m.path("value").asText().equalsIgnoreCase(roleName)) {
                    return ts;
                }
            }
        }
        return null;
    }

    private static Double sum(JsonNode series, String field) {
        double s = 0;
        int n = 0;
        for (JsonNode p : series.path("data")) {
            JsonNode x = p.get(field);
            if (x == null || !x.isNumber()) continue;
            s += x.asDouble();
            n++;
        }
        return n == 0 ? null : s;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
