package dev.kmads.consignado.gateway.http;

import dev.kmads.consignado.gateway.core.Json;
import java.util.LinkedHashMap;
import java.util.Map;

public record GatewayResponse(int status, Map<String, String> headers, byte[] body) {

    public static GatewayResponse json(int status, Object value) {
        return raw(status, "application/json", Json.bytes(value));
    }

    public static GatewayResponse raw(int status, String contentType, byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        return new GatewayResponse(status, headers, body);
    }
}
