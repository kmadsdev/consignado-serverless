package dev.kmads.consignado.gateway.functions;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import dev.kmads.consignado.gateway.App;
import dev.kmads.consignado.gateway.core.HostKind;
import dev.kmads.consignado.gateway.http.GatewayRequest;
import dev.kmads.consignado.gateway.http.GatewayResponse;
import dev.kmads.consignado.gateway.http.Handlers;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * The HTTP triggers. Thin: convert, call Handlers, convert back. Auth level is anonymous because the
 * gateway does its own token checks; only the budget hook uses a function key.
 */
public class HttpFunctions {

    @FunctionName("health")
    public HttpResponseMessage health(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.OPTIONS}, route = "health",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::health);
    }

    @FunctionName("engine")
    public HttpResponseMessage engine(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.OPTIONS}, route = "engine",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::engine);
    }

    @FunctionName("snapshot")
    public HttpResponseMessage snapshot(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.OPTIONS}, route = "snapshot",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::snapshot);
    }

    @FunctionName("contact")
    public HttpResponseMessage contact(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST, HttpMethod.OPTIONS}, route = "contact",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::contact);
    }

    @FunctionName("me")
    public HttpResponseMessage me(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.OPTIONS}, route = "me",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::me);
    }

    @FunctionName("profile")
    public HttpResponseMessage profile(
            @HttpTrigger(name = "req", methods = {HttpMethod.PUT, HttpMethod.OPTIONS}, route = "me/profile",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::profile);
    }

    @FunctionName("sessionsCreate")
    public HttpResponseMessage sessionsCreate(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST, HttpMethod.OPTIONS}, route = "sessions",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::sessionsCreate);
    }

    @FunctionName("sessionsGet")
    public HttpResponseMessage sessionsGet(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.OPTIONS}, route = "sessions/{id}",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            @BindingName("id") String id, ExecutionContext context) {
        return call(req, id, context, Handlers::sessionsGet);
    }

    @FunctionName("proxyFunctions")
    public HttpResponseMessage proxyFunctions(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT,
                    HttpMethod.DELETE, HttpMethod.OPTIONS}, route = "fn/{*path}",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            @BindingName("path") String path, ExecutionContext context) {
        return call(req, path, context, (h, r) -> h.proxy(HostKind.FUNCTIONS, r));
    }

    @FunctionName("proxyAks")
    public HttpResponseMessage proxyAks(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT,
                    HttpMethod.DELETE, HttpMethod.OPTIONS}, route = "aks/{*path}",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            @BindingName("path") String path, ExecutionContext context) {
        return call(req, path, context, (h, r) -> h.proxy(HostKind.AKS, r));
    }

    @FunctionName("admin")
    public HttpResponseMessage admin(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET, HttpMethod.POST, HttpMethod.OPTIONS},
                    route = "admin/{*path}",
                    authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> req,
            @BindingName("path") String path, ExecutionContext context) {
        return call(req, path, context, Handlers::admin);
    }

    @FunctionName("budgetHook")
    public HttpResponseMessage budgetHook(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, route = "hooks/budget",
                    authLevel = AuthorizationLevel.FUNCTION) HttpRequestMessage<Optional<String>> req,
            ExecutionContext context) {
        return call(req, null, context, Handlers::budgetHook);
    }

    private static HttpResponseMessage call(HttpRequestMessage<Optional<String>> req, String pathParam,
                                            ExecutionContext context,
                                            BiFunction<Handlers, GatewayRequest, GatewayResponse> route) {
        GatewayResponse response;
        try {
            byte[] body = req.getBody().map(b -> b.getBytes(StandardCharsets.UTF_8)).orElse(new byte[0]);
            GatewayRequest request = GatewayRequest.of(req.getHttpMethod().name(), pathParam,
                    req.getUri().getRawQuery(), req.getHeaders(), body);
            response = route.apply(App.handlers(), request);
        } catch (RuntimeException e) {
            // For example a missing environment variable. The message holds a name, never a value.
            context.getLogger().log(Level.SEVERE, "gateway failed: {0}",
                    e instanceof IllegalStateException ? e.getMessage() : e.getClass().getName());
            response = GatewayResponse.raw(500, "application/json",
                    "{\"code\":\"internal\",\"message\":\"Internal error\"}".getBytes(StandardCharsets.UTF_8));
        }
        HttpStatusType status = HttpStatusType.custom(response.status());
        HttpStatus known = null;
        for (HttpStatus s : HttpStatus.values()) {
            if (s.value() == response.status()) known = s;
        }
        HttpResponseMessage.Builder builder = known != null ? req.createResponseBuilder(known)
                : req.createResponseBuilder(status);
        response.headers().forEach(builder::header);
        if (response.body().length > 0) builder.body(response.body());
        return builder.build();
    }
}
