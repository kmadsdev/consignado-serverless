package dev.kmads.consignado.gateway.adapters;

import dev.kmads.consignado.gateway.core.Logs;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.rest.Response;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.kmads.consignado.gateway.core.Contact;
import dev.kmads.consignado.gateway.core.EngineRecord;
import dev.kmads.consignado.gateway.core.Json;
import dev.kmads.consignado.gateway.core.LifecycleEvent;
import dev.kmads.consignado.gateway.core.Session;
import dev.kmads.consignado.gateway.core.Times;
import dev.kmads.consignado.gateway.core.User;
import dev.kmads.consignado.gateway.ports.AuditLog;
import dev.kmads.consignado.gateway.ports.AuditStore;
import dev.kmads.consignado.gateway.ports.ContactStore;
import dev.kmads.consignado.gateway.ports.EngineStore;
import dev.kmads.consignado.gateway.ports.SessionStore;
import dev.kmads.consignado.gateway.ports.UserStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The five tables of the contract (users, sessions, engine, contacts, audit) in Azure Table Storage,
 * with the managed identity. The tables are made by Terraform; this code never creates or deletes a table.
 * Times are stored as ISO 8601 UTC text.
 */
public final class TableStores {
    private static final Logger LOG = Logger.getLogger("consignado.gateway");
    private static final String ETAG = "odata.etag";

    private final String endpoint;
    private final TokenCredential credential;

    public TableStores(String storageAccount, TokenCredential credential) {
        this.endpoint = "https://" + storageAccount + ".table.core.windows.net";
        this.credential = credential;
    }

    private TableClient table(String name) {
        return new TableClientBuilder().endpoint(endpoint).credential(credential).tableName(name).buildClient();
    }

    private static Optional<TableEntity> get(TableClient table, String pk, String rk) {
        try {
            return Optional.ofNullable(table.getEntity(pk, rk));
        } catch (TableServiceException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    private static List<TableEntity> query(TableClient table, String filter) {
        List<TableEntity> out = new ArrayList<>();
        table.listEntities(new ListEntitiesOptions().setFilter(filter), null, null).forEach(out::add);
        return out;
    }

    private static String str(TableEntity e, String key) {
        Object v = e.getProperty(key);
        return v == null ? null : v.toString();
    }

    private static String strOr(TableEntity e, String key, String fallback) {
        String v = str(e, key);
        return v == null ? fallback : v;
    }

    private static int num(TableEntity e, String key) {
        Object v = e.getProperty(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static boolean bool(TableEntity e, String key) {
        return Boolean.TRUE.equals(e.getProperty(key));
    }

    private static Instant time(TableEntity e, String key) {
        return Times.parse(str(e, key));
    }

    /** Deletes one row; a row that does not exist is not an error. */
    private static void remove(TableClient table, String pk, String rk) {
        try {
            table.deleteEntity(pk, rk);
        } catch (TableServiceException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) return;
            throw e;
        }
    }

    private static void put(TableEntity e, String key, Object value) {
        if (value != null) e.addProperty(key, value);
    }

    // ------------------------------------------------------------------ users

    public UserStore users() {
        TableClient table = table("users");
        return new UserStore() {
            @Override
            public Optional<User> find(String id) {
                return get(table, "u", id).map(TableStores::toUser);
            }

            @Override
            public void save(User u) {
                TableEntity e = new TableEntity("u", u.id);
                put(e, "email", u.email);
                put(e, "name", u.name);
                put(e, "company", u.company);
                put(e, "linkedin", u.linkedin);
                put(e, "status", u.status);
                put(e, "windowsUsed", u.windowsUsed);
                put(e, "windowsMax", u.windowsMax);
                put(e, "windowMinutes", u.windowMinutes);
                put(e, "flags", String.join(",", u.flags));
                put(e, "ipHash", u.ipHash);
                put(e, "fingerprintHash", u.fingerprintHash);
                put(e, "createdAt", Times.iso(u.createdAt));
                put(e, "lastSeenAt", Times.iso(u.lastSeenAt));
                put(e, "consentAt", Times.iso(u.consentAt));
                put(e, "approvedByAdmin", u.approvedByAdmin);
                put(e, "activeSessionId", u.activeSessionId);
                put(e, "mailboxHash", u.mailboxHash);
                try {
                    Response<Void> response;
                    if (u.etag == null) {
                        response = table.upsertEntityWithResponse(e, TableEntityUpdateMode.REPLACE, null, null);
                    } else {
                        // If-Match: a row that changed after the read is not overwritten (412 -> conflict).
                        e.addProperty(ETAG, u.etag);
                        response = table.updateEntityWithResponse(e, TableEntityUpdateMode.REPLACE, true, null, null);
                    }
                    String etag = response.getHeaders().getValue("ETag");
                    if (etag != null) u.etag = etag;
                } catch (TableServiceException ex) {
                    if (ex.getResponse() != null && ex.getResponse().getStatusCode() == 412) {
                        throw new UserStore.ConflictException();
                    }
                    throw ex;
                }
            }

            @Override
            public List<User> list() {
                return query(table, "PartitionKey eq 'u'").stream().map(TableStores::toUser).toList();
            }

            @Override
            public void delete(String id) {
                remove(table, "u", id);
            }
        };
    }

    private static User toUser(TableEntity e) {
        User u = new User();
        u.id = e.getRowKey();
        u.email = strOr(e, "email", "");
        u.name = strOr(e, "name", "");
        u.company = strOr(e, "company", "");
        u.linkedin = strOr(e, "linkedin", "");
        u.status = strOr(e, "status", User.ACTIVE);
        u.windowsUsed = num(e, "windowsUsed");
        u.windowsMax = num(e, "windowsMax");
        u.windowMinutes = num(e, "windowMinutes");
        u.flags = new TreeSet<>();
        for (String flag : strOr(e, "flags", "").split(",")) {
            if (!flag.isBlank()) u.flags.add(flag);
        }
        u.ipHash = str(e, "ipHash");
        u.fingerprintHash = str(e, "fingerprintHash");
        u.createdAt = time(e, "createdAt");
        u.lastSeenAt = time(e, "lastSeenAt");
        u.consentAt = time(e, "consentAt");
        u.approvedByAdmin = bool(e, "approvedByAdmin");
        u.activeSessionId = str(e, "activeSessionId");
        u.mailboxHash = str(e, "mailboxHash");
        u.etag = e.getETag();
        return u;
    }

    // --------------------------------------------------------------- sessions

    public SessionStore sessions() {
        TableClient table = table("sessions");
        return new SessionStore() {
            @Override
            public Optional<Session> find(String id) {
                return get(table, "s", id).map(TableStores::toSession);
            }

            @Override
            public void save(Session s) {
                TableEntity e = new TableEntity("s", s.id);
                put(e, "userId", s.userId);
                put(e, "state", s.state);
                put(e, "requestedAt", Times.iso(s.requestedAt));
                put(e, "readyAt", Times.iso(s.readyAt));
                put(e, "expiresAt", Times.iso(s.expiresAt));
                put(e, "endedAt", Times.iso(s.endedAt));
                put(e, "lastObservedAt", Times.iso(s.lastObservedAt));
                put(e, "events", Json.string(s.events));
                put(e, "open", s.open);
                put(e, "requests", s.requests);
                try {
                    Response<Void> response;
                    if (s.etag == null) {
                        response = table.upsertEntityWithResponse(e, TableEntityUpdateMode.REPLACE, null, null);
                    } else {
                        e.addProperty(ETAG, s.etag);
                        response = table.updateEntityWithResponse(e, TableEntityUpdateMode.REPLACE, true, null, null);
                    }
                    String etag = response.getHeaders().getValue("ETag");
                    if (etag != null) s.etag = etag;
                } catch (TableServiceException ex) {
                    if (ex.getResponse() != null && ex.getResponse().getStatusCode() == 412) {
                        throw new ConflictException();
                    }
                    throw ex;
                }
            }

            @Override
            public List<Session> listOpen() {
                return query(table, "PartitionKey eq 's' and open eq true").stream().map(TableStores::toSession).toList();
            }

            @Override
            public List<Session> list() {
                return query(table, "PartitionKey eq 's'").stream().map(TableStores::toSession).toList();
            }

            @Override
            public void delete(String id) {
                remove(table, "s", id);
            }
        };
    }

    private static Session toSession(TableEntity e) {
        Session s = new Session();
        s.id = e.getRowKey();
        s.userId = strOr(e, "userId", "");
        s.state = strOr(e, "state", Session.ENDED);
        s.requestedAt = time(e, "requestedAt");
        s.readyAt = time(e, "readyAt");
        s.expiresAt = time(e, "expiresAt");
        s.endedAt = time(e, "endedAt");
        s.lastObservedAt = time(e, "lastObservedAt");
        s.open = bool(e, "open");
        s.requests = num(e, "requests");
        s.etag = e.getETag();
        try {
            s.events = new ArrayList<>(Json.MAPPER.readValue(strOr(e, "events", "[]"),
                    new TypeReference<List<LifecycleEvent>>() {}));
        } catch (Exception ex) {
            s.events = new ArrayList<>();
        }
        return s;
    }

    // ----------------------------------------------------------------- engine

    public EngineStore engine() {
        TableClient table = table("engine");
        return new EngineStore() {
            @Override
            public EngineRecord load() {
                EngineRecord r = new EngineRecord();
                get(table, "e", "state").ifPresent(e -> {
                    r.state = strOr(e, "state", EngineRecord.STOPPED);
                    r.since = time(e, "since");
                    r.paused = bool(e, "paused");
                    r.pausedReason = str(e, "pausedReason");
                    r.lastStopAt = time(e, "lastStopAt");
                    r.activeSessions = num(e, "activeSessions");
                    r.externalSince = time(e, "externalSince");
                    r.lastSnapshotAt = time(e, "lastSnapshotAt");
                    r.lastRetentionAt = time(e, "lastRetentionAt");
                    r.lastBudgetCheckAt = time(e, "lastBudgetCheckAt");
                });
                return r;
            }

            @Override
            public void save(EngineRecord r) {
                TableEntity e = new TableEntity("e", "state");
                put(e, "state", r.state);
                put(e, "since", Times.iso(r.since));
                put(e, "paused", r.paused);
                put(e, "pausedReason", r.pausedReason);
                put(e, "lastStopAt", Times.iso(r.lastStopAt));
                put(e, "activeSessions", r.activeSessions);
                put(e, "externalSince", Times.iso(r.externalSince));
                put(e, "lastSnapshotAt", Times.iso(r.lastSnapshotAt));
                put(e, "lastRetentionAt", Times.iso(r.lastRetentionAt));
                put(e, "lastBudgetCheckAt", Times.iso(r.lastBudgetCheckAt));
                table.upsertEntityWithResponse(e, TableEntityUpdateMode.REPLACE, null, null);
            }
        };
    }

    // --------------------------------------------------------------- contacts

    public ContactStore contacts() {
        TableClient table = table("contacts");
        return new ContactStore() {
            @Override
            public void save(Contact c) {
                TableEntity e = new TableEntity("c", c.id());
                put(e, "kind", c.kind());
                put(e, "name", c.name());
                put(e, "email", c.email());
                put(e, "message", c.message());
                put(e, "createdAt", c.createdAt());
                put(e, "userId", c.userId());
                table.upsertEntityWithResponse(e, TableEntityUpdateMode.REPLACE, null, null);
            }

            @Override
            public List<Contact> list() {
                return query(table, "PartitionKey eq 'c'").stream()
                        .map(e -> new Contact(e.getRowKey(), strOr(e, "kind", "contact"), strOr(e, "name", ""),
                                strOr(e, "email", ""), strOr(e, "message", ""), strOr(e, "createdAt", ""),
                                str(e, "userId")))
                        .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).toList();
            }

            @Override
            public void delete(String id) {
                remove(table, "c", id);
            }
        };
    }

    // ------------------------------------------------------------------ audit

    /**
     * Retention of the audit table: the partition key is the day (yyyy-mm-dd), so the rows older than
     * a day are one range query. Only the keys are read.
     */
    public AuditStore auditRetention() {
        TableClient table = table("audit");
        return day -> {
            int deleted = 0;
            try {
                String filter = "PartitionKey lt '" + day + "'";
                for (TableEntity e : table.listEntities(new ListEntitiesOptions().setFilter(filter)
                        .setSelect(List.of("PartitionKey", "RowKey")), null, null)) {
                    remove(table, e.getPartitionKey(), e.getRowKey());
                    deleted++;
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, Logs.fmt("audit retention failed after {0} rows: {1}", deleted, Logs.of(ex)));
            }
            return deleted;
        };
    }

    public AuditLog audit() {
        TableClient table = table("audit");
        SecureRandom random = new SecureRandom();
        return (who, action, target, ipHash) -> {
            try {
                Instant now = Instant.now();
                byte[] suffix = new byte[4];
                random.nextBytes(suffix);
                TableEntity e = new TableEntity(now.atOffset(ZoneOffset.UTC).toLocalDate().toString(),
                        Times.iso(now).replace(":", "") + "-" + HexFormat.of().formatHex(suffix));
                put(e, "who", who);
                put(e, "action", action);
                put(e, "target", target);
                put(e, "ipHash", ipHash);
                table.createEntity(e);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, Logs.fmt("audit write failed: {0}", ex.getClass().getSimpleName()));
            }
        };
    }
}
