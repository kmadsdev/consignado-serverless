package dev.kmads.consignado.gateway.core;

/** An error that is safe to show to the caller: status, short code, generic message. */
public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Integer retryAfterSeconds;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }

    public static ApiException badRequest(String message) { return new ApiException(400, "bad-request", message); }
    public static ApiException unauthorized() { return new ApiException(401, "unauthorized", "Sign in is required"); }
    public static ApiException forbidden() { return new ApiException(403, "forbidden", "Not allowed"); }
    public static ApiException notFound() { return new ApiException(404, "not-found", "Not found"); }
    public static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
}
