package dev.kmads.consignado.gateway;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import dev.kmads.consignado.gateway.adapters.ArmClusterControl;
import dev.kmads.consignado.gateway.adapters.AzureTokens;
import dev.kmads.consignado.gateway.adapters.BlobSnapshotStore;
import dev.kmads.consignado.gateway.adapters.CostManagementReader;
import dev.kmads.consignado.gateway.adapters.EntraTokenVerifier;
import dev.kmads.consignado.gateway.adapters.HttpUpstream;
import dev.kmads.consignado.gateway.adapters.KeyVaultSecrets;
import dev.kmads.consignado.gateway.adapters.KubeClusterObserver;
import dev.kmads.consignado.gateway.adapters.Mailers;
import dev.kmads.consignado.gateway.adapters.TableStores;
import dev.kmads.consignado.gateway.adapters.TurnstileBotCheck;
import dev.kmads.consignado.gateway.core.AdminService;
import dev.kmads.consignado.gateway.core.ContactService;
import dev.kmads.consignado.gateway.core.EngineService;
import dev.kmads.consignado.gateway.core.Hasher;
import dev.kmads.consignado.gateway.core.ProxyService;
import dev.kmads.consignado.gateway.core.RateGuard;
import dev.kmads.consignado.gateway.core.Settings;
import dev.kmads.consignado.gateway.core.SnapshotService;
import dev.kmads.consignado.gateway.core.UserService;
import dev.kmads.consignado.gateway.http.Handlers;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.BotCheck;
import dev.kmads.consignado.gateway.ports.EngineStore;
import dev.kmads.consignado.gateway.ports.Mailer;
import dev.kmads.consignado.gateway.ports.Secrets;
import dev.kmads.consignado.gateway.ports.SessionStore;
import dev.kmads.consignado.gateway.ports.Upstream;
import dev.kmads.consignado.gateway.ports.UserStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/** The composition root: builds every object once, by hand. No framework, so the cold start stays short. */
public final class App {
    private static volatile Handlers handlers;

    private App() {}

    public static Handlers handlers() {
        Handlers h = handlers;
        if (h == null) {
            synchronized (App.class) {
                if (handlers == null) handlers = build(Config.fromEnvironment());
                h = handlers;
            }
        }
        return h;
    }

    static Handlers build(Config config) {
        Clock clock = Clock.systemUTC();
        Settings settings = config.settings();
        TokenCredential credential = new ManagedIdentityCredentialBuilder().clientId(config.clientId()).build();
        AzureTokens tokens = new AzureTokens(credential);

        TableStores tables = new TableStores(config.storageAccount(), credential);
        UserStore userStore = tables.users();
        SessionStore sessionStore = tables.sessions();
        EngineStore engineStore = tables.engine();
        AuditLog audit = tables.audit();

        Secrets secrets = new KeyVaultSecrets(config.keyVaultUrl(), credential);
        Hasher hasher = new Hasher(secrets);
        BotCheck botCheck = new TurnstileBotCheck(secrets);
        Mailer mailer = config.acsEndpoint() == null ? Mailers.auditOnly(audit)
                : Mailers.acs(config.acsEndpoint(), config.acsSender(), config.ownerEmail(), credential, audit);
        Upstream upstream = new HttpUpstream(config.apifnBaseUrl(), config.aksInternalUrl());
        Supplier<String> ids = App::randomId;

        UserService users = new UserService(userStore, mailer, botCheck, hasher, audit, settings, clock);
        EngineService engine = new EngineService(userStore, sessionStore, engineStore,
                new ArmClusterControl(tokens, config.aksResourceId()),
                new KubeClusterObserver(tokens, config.aksApiServerUrl(), config.aksCaCertB64()),
                upstream, audit, settings, clock, ids);
        ProxyService proxy = new ProxyService(upstream, secrets, sessionStore, clock, System::nanoTime);
        ContactService contacts = new ContactService(tables.contacts(), mailer, botCheck, hasher, audit, clock, ids);
        AdminService admin = new AdminService(userStore, engine,
                new CostManagementReader(tokens, config.aksResourceId()), audit, settings, clock);
        SnapshotService snapshot = new SnapshotService(upstream, secrets,
                new BlobSnapshotStore(tokens, config.storageAccount()), engineStore, clock);

        return new Handlers(EntraTokenVerifier.forTenant(config.tenantId(), config.apiAudience()), users, engine,
                proxy, contacts, admin, snapshot, secrets, hasher, audit, settings, config.allowDevOrigin(),
                System::nanoTime, new RateGuard(5, Duration.ofMinutes(10), clock),
                new RateGuard(3, Duration.ofMinutes(10), clock));
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /** 16 random characters, lower-case letters and digits: the session id is the <clusterid> of the URL. */
    static String randomId() {
        char[] out = new char[16];
        for (int i = 0; i < out.length; i++) out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new String(out);
    }
}
