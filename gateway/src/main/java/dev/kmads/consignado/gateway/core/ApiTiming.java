package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

/**
 * The parsed response header X-Consignado-Timing of the Spring Boot API (contract addendum 2, part 1).
 * It is input from another service: {@link #parse} accepts it only when every field is in a sane range,
 * and returns null on any doubt (the trace is then built as without the header).
 */
public record ApiTiming(String instance, double appMs, Db db) {
    public record Db(double ms, int statements, int select, int insert, int update, int delete, int other,
                     double connectionMs, int transactions) {}

    public static final String HEADER = "x-consignado-timing";
    /** A valid header is about 200 characters; 1 KB leaves room and stops a large value early. */
    public static final int MAX_CHARS = 1024;
    /** No request of the API takes longer than the longest upstream timeout (30 s); 60 s leaves room. */
    public static final double MAX_MS = 60_000;
    public static final int MAX_COUNT = 10_000;
    private static final Pattern INSTANCE = Pattern.compile("^[A-Za-z0-9._-]{1,80}$");

    /** null when the header is missing or not valid. Unknown fields are ignored and never passed on. */
    public static ApiTiming parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_CHARS) return null;
        try {
            JsonNode n = Json.MAPPER.readTree(raw);
            if (n == null || !n.isObject()) return null;
            JsonNode instance = n.get("instance");
            if (instance == null || !instance.isTextual() || !INSTANCE.matcher(instance.asText()).matches()) {
                return null;
            }
            Double appMs = ms(n.get("appMs"));
            if (appMs == null) return null;
            JsonNode db = n.get("db");
            Db parsedDb = null;
            if (db != null && !db.isNull()) {
                if (!db.isObject()) return null;
                Double dbMs = ms(db.get("ms"));
                Double connectionMs = ms(db.get("connectionMs"));
                Integer statements = count(db.get("statements"));
                Integer select = count(db.get("select"));
                Integer insert = count(db.get("insert"));
                Integer update = count(db.get("update"));
                Integer delete = count(db.get("delete"));
                Integer other = count(db.get("other"));
                Integer transactions = count(db.get("transactions"));
                if (dbMs == null || connectionMs == null || statements == null || select == null || insert == null
                        || update == null || delete == null || other == null || transactions == null) {
                    return null;
                }
                if (select + insert + update + delete + other != statements) return null;
                // Statement time is part of the application time (1 ms of rounding is allowed).
                if (dbMs > appMs + 1) return null;
                parsedDb = new Db(dbMs, statements, select, insert, update, delete, other, connectionMs,
                        transactions);
            }
            return new ApiTiming(instance.asText(), appMs, parsedDb);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the API reported at least one SQL statement for this request. */
    public boolean touchedDatabase() {
        return db != null && db.statements > 0;
    }

    /** "3 statements: 2 select, 1 insert" (kinds with 0 are left out). */
    public String databaseDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append(db.statements).append(db.statements == 1 ? " statement: " : " statements: ");
        String sep = "";
        int[] counts = {db.select, db.insert, db.update, db.delete, db.other};
        String[] names = {"select", "insert", "update", "delete", "other"};
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            sb.append(sep).append(counts[i]).append(' ').append(names[i]);
            sep = ", ";
        }
        return sb.toString();
    }

    private static Double ms(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        double v = n.asDouble();
        return Double.isFinite(v) && v >= 0 && v <= MAX_MS ? v : null;
    }

    private static Integer count(JsonNode n) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt()) return null;
        int v = n.asInt();
        return v >= 0 && v <= MAX_COUNT ? v : null;
    }
}
