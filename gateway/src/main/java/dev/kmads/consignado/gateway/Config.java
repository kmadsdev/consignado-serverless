package dev.kmads.consignado.gateway;

import dev.kmads.consignado.gateway.core.Settings;
import java.time.Duration;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Every environment variable of the gateway. An error names the variable and never shows a value. */
public record Config(
        String clientId, String tenantId, String apiAudience, String adminOid, String aksResourceId,
        String aksApiServerUrl, String aksCaCertB64, String aksInternalUrl, String apifnBaseUrl,
        String keyVaultUrl, String storageAccount, String ownerEmail, int windowMinutes, int windowsMax,
        int cooldownMinutes, double budgetLimit, String budgetCurrency, String acsEndpoint, String acsSender, boolean allowDevOrigin,
        boolean edgeKeyRequired, int dailyWindowsMax, String spaClientId, java.util.List<String> turnstileHostnames,
        String gatewayAppId, String apiAppId, String keyVaultId, String postgresId, String appInsightsId) {

    /** The only value of EDGE that turns the edge-key check off. Missing or any other value: the key is required. */
    public static final String EDGE_LOCAL = "local";
    public static final String DEFAULT_TURNSTILE_HOSTNAMES = "consignado.kmads.dev,cluster.consignado.kmads.dev";

    private static final Pattern AKS_ID = Pattern.compile(
            "^/subscriptions/[0-9a-fA-F\\-]{36}/resourceGroups/[A-Za-z0-9_.()\\-]{1,90}"
                    + "/providers/Microsoft\\.ContainerService/managedClusters/[A-Za-z0-9_\\-]{1,63}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STORAGE = Pattern.compile("^[a-z0-9]{3,24}$");

    public static Config fromEnvironment() {
        return load(System::getenv);
    }

    public static Config load(Function<String, String> env) {
        Config c = new Config(
                required(env, "AZURE_CLIENT_ID"),
                required(env, "ENTRA_TENANT_ID"),
                required(env, "ENTRA_API_AUDIENCE"),
                required(env, "ADMIN_OID"),
                required(env, "AKS_RESOURCE_ID"),
                url(env, "AKS_API_SERVER_URL", true),
                required(env, "AKS_CA_CERT_B64"),
                url(env, "AKS_INTERNAL_URL", false),
                url(env, "APIFN_BASE_URL", true),
                url(env, "KEY_VAULT_URL", true),
                required(env, "STORAGE_ACCOUNT"),
                required(env, "OWNER_EMAIL"),
                number(env, "WINDOW_MINUTES", 1, 120),
                number(env, "WINDOWS_MAX", 1, 100),
                number(env, "COOLDOWN_MINUTES", 0, 240),
                // BUDGET_LIMIT, in the billing currency. BUDGET_LIMIT_USD is the old name (the value was never USD):
                // read it when BUDGET_LIMIT is absent, so the app settings and the code can be deployed in either order.
                decimal(env, optional(env, "BUDGET_LIMIT") != null ? "BUDGET_LIMIT" : "BUDGET_LIMIT_USD"),
                currency(env),
                optional(env, "ACS_ENDPOINT"),
                sender(env),
                "true".equalsIgnoreCase(optional(env, "ALLOW_DEV_ORIGIN")),
                // Fail closed: a typo or a missing value keeps the edge key on.
                !EDGE_LOCAL.equals(optional(env, "EDGE")),
                optionalNumber(env, "DAILY_WINDOWS_MAX", Settings.DEFAULT_DAILY_WINDOWS_MAX, 1, 1000),
                optional(env, "ENTRA_SPA_CLIENT_ID"),
                java.util.Arrays.stream(java.util.Objects.requireNonNullElse(optional(env, "TURNSTILE_HOSTNAMES"),
                        DEFAULT_TURNSTILE_HOSTNAMES).split(",")).map(String::strip).filter(h -> !h.isEmpty()).toList(),
                // Status page (contract addendum): optional; a missing id makes that service "unknown".
                resourceId(env, "GATEWAY_APP_ID"),
                resourceId(env, "API_APP_ID"),
                resourceId(env, "KEY_VAULT_ID"),
                resourceId(env, "POSTGRES_ID"),
                resourceId(env, "APPINSIGHTS_ID"));
        if (!AKS_ID.matcher(c.aksResourceId).matches()) throw invalid("AKS_RESOURCE_ID");
        if (!STORAGE.matcher(c.storageAccount).matches()) throw invalid("STORAGE_ACCOUNT");
        return c;
    }

    public Settings settings() {
        return new Settings(adminOid, Duration.ofMinutes(windowMinutes), windowsMax,
                Duration.ofMinutes(cooldownMinutes), budgetLimit, budgetCurrency, Duration.ofMinutes(15), Duration.ofMinutes(20), dailyWindowsMax);
    }

    /** The sender address: EMAIL_SENDER (the name that Terraform sets) or ACS_SENDER. */
    private static String sender(Function<String, String> env) {
        String v = optional(env, "EMAIL_SENDER");
        return v != null ? v : optional(env, "ACS_SENDER");
    }

    private static String optional(Function<String, String> env, String name) {
        String v = env.apply(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String required(Function<String, String> env, String name) {
        String v = optional(env, name);
        if (v == null) throw missing(name);
        return v;
    }

    private static String url(Function<String, String> env, String name, boolean httpsOnly) {
        String v = required(env, name);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        boolean ok = v.startsWith("https://") || (!httpsOnly && v.startsWith("http://"));
        if (!ok) throw invalid(name);
        return v;
    }

    private static int number(Function<String, String> env, String name, int min, int max) {
        try {
            int n = Integer.parseInt(required(env, name));
            if (n < min || n > max) throw invalid(name);
            return n;
        } catch (NumberFormatException e) {
            throw invalid(name);
        }
    }

    /** An ARM resource id, or null. A value that is not an id is an error (it would go into a URL). */
    private static String resourceId(Function<String, String> env, String name) {
        String v = optional(env, name);
        if (v == null) return null;
        if (!RESOURCE_ID.matcher(v).matches()) throw invalid(name);
        return v;
    }

    private static final Pattern RESOURCE_ID = Pattern.compile(
            "^/subscriptions/[0-9a-fA-F\\-]{36}/resourceGroups/[A-Za-z0-9_.()\\-]{1,90}/providers/[A-Za-z0-9./_\\-]{3,200}$");

    private static int optionalNumber(Function<String, String> env, String name, int fallback, int min, int max) {
        return optional(env, name) == null ? fallback : number(env, name, min, max);
    }

    /** BUDGET_CURRENCY: an ISO 4217 code; default BRL (the billing currency of this subscription). */
    private static String currency(Function<String, String> env) {
        String v = optional(env, "BUDGET_CURRENCY");
        if (v == null) return Settings.DEFAULT_BUDGET_CURRENCY;
        if (!v.matches("[A-Za-z]{3}")) throw invalid("BUDGET_CURRENCY");
        return v.toUpperCase(java.util.Locale.ROOT);
    }

    private static double decimal(Function<String, String> env, String name) {
        try {
            double d = Double.parseDouble(required(env, name));
            if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) throw invalid(name);
            return d;
        } catch (NumberFormatException e) {
            throw invalid(name);
        }
    }

    private static IllegalStateException missing(String name) {
        return new IllegalStateException("Missing environment variable: " + name);
    }

    private static IllegalStateException invalid(String name) {
        return new IllegalStateException("Invalid environment variable: " + name);
    }

    @Override
    public String toString() {
        return "Config[values hidden]";
    }
}
