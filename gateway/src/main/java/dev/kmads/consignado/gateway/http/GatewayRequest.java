package dev.kmads.consignado.gateway.http;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** An HTTP request without Azure types. Header names are lower-case. */
public record GatewayRequest(String method, String pathParam, String rawQuery, Map<String, String> headers,
                             byte[] body) {

    public static GatewayRequest of(String method, String pathParam, String rawQuery, Map<String, String> headers,
                                    byte[] body) {
        Map<String, String> lower = new TreeMap<>();
        if (headers != null) headers.forEach((k, v) -> { if (k != null && v != null) lower.put(k.toLowerCase(Locale.ROOT), v); });
        return new GatewayRequest(method == null ? "" : method.toUpperCase(Locale.ROOT), pathParam, rawQuery, lower,
                body == null ? new byte[0] : body);
    }

    public String header(String name) {
        return headers.get(name);
    }
}
