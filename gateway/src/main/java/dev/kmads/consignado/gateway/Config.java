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
        int cooldownMinutes, double budgetLimitUsd, String acsEndpoint, String acsSender, boolean allowDevOrigin,
        boolean edgeCloudflare) {

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
                decimal(env, "BUDGET_LIMIT_USD"),
                optional(env, "ACS_ENDPOINT"),
                optional(env, "ACS_SENDER"),
                "true".equalsIgnoreCase(optional(env, "ALLOW_DEV_ORIGIN")),
                "cloudflare".equals(optional(env, "EDGE")));
        if (!AKS_ID.matcher(c.aksResourceId).matches()) throw invalid("AKS_RESOURCE_ID");
        if (!STORAGE.matcher(c.storageAccount).matches()) throw invalid("STORAGE_ACCOUNT");
        if (c.acsEndpoint != null && c.acsSender == null) throw missing("ACS_SENDER");
        return c;
    }

    public Settings settings() {
        return new Settings(adminOid, Duration.ofMinutes(windowMinutes), windowsMax,
                Duration.ofMinutes(cooldownMinutes), budgetLimitUsd, Duration.ofMinutes(15), Duration.ofMinutes(20));
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
