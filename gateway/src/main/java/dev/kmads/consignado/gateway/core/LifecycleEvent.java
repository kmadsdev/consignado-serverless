package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One step of a request or of an engine start. Same shape as the frontend type. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LifecycleEvent(String ts, String stage, String status, String detail, Long ms) {
    public static final String PENDING = "pending";
    public static final String ACTIVE = "active";
    public static final String DONE = "done";
    public static final String ERROR = "error";
}
