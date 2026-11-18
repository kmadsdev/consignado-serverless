package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.kmads.consignado.gateway.ports.EngineStore;
import dev.kmads.consignado.gateway.ports.Secrets;
import dev.kmads.consignado.gateway.ports.SnapshotStore;
import dev.kmads.consignado.gateway.ports.Upstream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The masked copy of the shared data: read from the Functions API host, written to a blob. */
public final class SnapshotService {
    public static final Duration REFRESH_EVERY = Duration.ofMinutes(10);
    public static final String HOST_KEY = "apifn-host-key";
    private static final Duration MEMORY_TTL = Duration.ofSeconds(60);

    private final Upstream upstream;
    private final Secrets secrets;
    private final SnapshotStore store;
    private final EngineStore engine;
    private final Clock clock;
    private volatile String cached;
    private volatile Instant cachedAt;

    public SnapshotService(Upstream upstream, Secrets secrets, SnapshotStore store, EngineStore engine, Clock clock) {
        this.upstream = upstream;
        this.secrets = secrets;
        this.store = store;
        this.engine = engine;
        this.clock = clock;
    }

    /** The JSON for GET /api/snapshot. An empty snapshot when the blob does not exist yet. */
    public String current() {
        Instant now = clock.instant();
        String c = cached;
        if (c != null && cachedAt != null && Duration.between(cachedAt, now).compareTo(MEMORY_TTL) < 0) return c;
        String json;
        try {
            json = store.read().orElse(null);
        } catch (RuntimeException e) {
            json = null;
        }
        if (json == null) {
            if (c != null) return c;
            json = Json.string(empty(now));
        }
        cached = json;
        cachedAt = now;
        return json;
    }

    /** Called by the timer each minute. Refreshes when the last refresh is 10 minutes old. True when it wrote. */
    public boolean refreshIfDue() {
        Instant now = clock.instant();
        EngineRecord rec = engine.load();
        if (rec.lastSnapshotAt != null && Duration.between(rec.lastSnapshotAt, now).compareTo(REFRESH_EVERY) < 0) {
            return false;
        }
        boolean ok = refresh();
        // The time is written also after a failure: a broken API host is not called each minute.
        EngineRows.updateQuietly(engine, r -> r.lastSnapshotAt = now, "snapshot time");
        return ok;
    }

    public boolean refresh() {
        Secrets.Read key = secrets.read(HOST_KEY);
        if (!key.found()) return false;
        try {
            ObjectNode out = Json.MAPPER.createObjectNode();
            out.put("asOf", Times.iso(clock.instant()));
            out.set("agreements", fetch("api/v1/agreements", null, key.value()));
            out.set("customers", Masker.maskPersons(fetch("api/v1/customers", "page=0&size=50", key.value())));
            out.set("contracts", Masker.maskPersons(fetch("api/v1/contracts", "page=0&size=50", key.value())));
            String json = Json.string(out);
            store.write(json);
            cached = json;
            cachedAt = clock.instant();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ArrayNode fetch(String path, String query, String hostKey) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-Consignado-User", "gateway-snapshot");
        headers.put("x-functions-key", hostKey);
        Upstream.Response r = upstream.call(new Upstream.Request(HostKind.FUNCTIONS, "GET", path, query, headers, null));
        if (r.status() != 200) throw new IllegalStateException("snapshot source status " + r.status());
        JsonNode root = Json.MAPPER.readTree(r.body());
        JsonNode list = root.isArray() ? root : root.path("content");
        return list instanceof ArrayNode array ? array : Json.MAPPER.createArrayNode();
    }

    private static Map<String, Object> empty(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", Times.iso(now));
        m.put("agreements", java.util.List.of());
        m.put("customers", java.util.List.of());
        m.put("contracts", java.util.List.of());
        return m;
    }
}
