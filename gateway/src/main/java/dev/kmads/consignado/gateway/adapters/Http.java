package dev.kmads.consignado.gateway.adapters;

import java.net.http.HttpClient;
import java.time.Duration;

final class Http {
    private Http() {}

    static final Duration CONNECT = Duration.ofSeconds(5);
    /** One client for the calls to public Azure and Cloudflare endpoints. It never follows a redirect. */
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(CONNECT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
}
