# consignado gateway

A Java 21 Azure Functions app (`func-consignado-gw-km01`, Flex Consumption). It is the only door to the two API hosts of the consignado platform. It checks the Entra ID token, applies the access rules, starts and stops the AKS cluster for 15-minute access windows, and returns a trace of every proxied call.

Plain Java: no Spring, no dependency injection framework. All objects are built once in `App.java`.

## Layout

| Package | Content |
|---|---|
| `core/` | The rules, in pure Java: users, duplicate check, engine state machine, access windows, lifecycle events, proxy rules, masking, trace. No Azure type. The clock is injected. |
| `ports/` | Interfaces: `TokenVerifier`, `UserStore`, `SessionStore`, `EngineStore`, `ContactStore`, `AuditLog`, `ClusterControl`, `ClusterObserver`, `Upstream`, `Mailer`, `BotCheck`, `Secrets`, `CostReader`, `SnapshotStore`. |
| `http/` | `Handlers`: every route without Azure types (edge key, token check, errors, response headers). |
| `adapters/` | Entra ID JWT (nimbus-jose-jwt), Azure Tables, Key Vault, ARM (plain HTTPS), Kubernetes API (plain HTTPS), upstream HTTP client, Turnstile, Azure Communication Services email, Cost Management, Blob REST. |
| `functions/` | Thin HTTP triggers and one timer trigger. |

## Routes (all under `/api`)

| Method and path | Who | Result |
|---|---|---|
| `GET /api/health` | all | `{status:"UP"}`, a constant: no configuration, no Azure call, no edge key |
| `GET /api/engine` | all | `Engine` `{state, since, activeSessions, pausedReason, paused}` (see "Engine rules" for the pause combinations) |
| `GET /api/status?host=functions\|aks` | all for `functions`; token for `aks` | `PlatformStatus` (see "Status page"); 400 for another `host` |
| `GET /api/snapshot` | all | masked copy of shared data `{asOf, agreements, customers, contracts}` |
| `POST /api/contact` | all + Turnstile token | `ContactRequest` (201), email to the owner. Kinds `contact` and `more-windows`. A signed-in sender is linked: the row has `userId` (null for a guest), and `GET /api/admin/contacts` shows it |
| `GET /api/me` | token | `Me`; the first call creates the user and emails the owner |
| `PUT /api/me/profile` | token + Turnstile token | `Profile` + `{fingerprint, turnstileToken}`; duplicate check; `Me` |
| `POST /api/sessions` | user | `Session` (201; 200 when the user already has an active window). 409 `paused`, `held`, `blocked`, `no-windows`, `profile-incomplete`, `cooldown` (with `Retry-After`), `daily-limit` (`DAILY_WINDOWS_MAX`, default 12, new windows in any 24 hours over all accounts; the owner is not limited), `busy` (the account row changed twice at the same time) |
| `GET /api/sessions/{id}` | owner or admin | `Session` with events and `requests` (proxied AKS calls of the window that the API host answered with 2xx or 3xx; one table write per call, ETag, one retry). A poll also reads the real cluster signals (at most every 3 s) |
| `POST /api/sessions/{id}/end` | owner or admin | ends the window early: `Session` in state `ended` (200; an ended or failed session comes back unchanged). The window is not given back to the quota (a `starting` window is counted now). The timer then stops the cluster when no window is active. 404 for another user's session |
| `GET, POST, PATCH /api/fn/{*path}` | token; guest only `POST api/v1/simulations/preview` (503 `paused` while the engine is paused for budget) | proxy to the Functions API host. Contract state changes need `If-Match` (428 without it; see "Version checks") |
| `GET, POST, PATCH /api/aks/{*path}` | token + own window in state `ready` | proxy to the AKS host. Same `If-Match` rule |
| `GET /api/admin/users`, `POST /api/admin/users/{id}` | admin | `AdminUser[]` / `AdminUser`; actions `approve`, `hold`, `block`, `grant` (`windows` 1 to 20, default 1, added to the maximum), `set` (`windowsMax` 0 to 50 and/or `windowMinutes` 5 to 60, whole numbers; audit `admin.user.set`), `delete` (see "Personal data"). `Me` and `AdminUser` carry `windowMinutes` (default `WINDOW_MINUTES`); a new window lasts the owner's `windowMinutes` at the moment it opens, and a change does not alter an open window |
| `GET /api/admin/sessions`, `/state`, `/contacts` | admin | `Session[]`, `AdminState`, `ContactRequest[]` |
| `POST /api/admin/pause` `{paused}`, `POST /api/admin/engine/stop` | admin | `AdminState` |
| `POST /api/hooks/budget` | function key | spend at or above the budget: pauses (reason `budget`) and stops the cluster; spend below: audit only; a body without numeric `data.SpendingAmount` and `data.Budget`: 400, no pause |
| timer `reaper`, every minute | - | advances sessions, ends windows, stops the idle cluster, checks the month-to-date cost every 30 minutes (at or above `BUDGET_LIMIT_USD`: same pause as the budget hook), refreshes the snapshot every 10 minutes, runs the retention job once per day |

Errors are `{code, message}`. On an upstream failure the failed stage of the trace has `status: "error"` and a short `detail` (`upstream answered 503`, `timeout after 30 s` (Functions) / `10 s` (AKS), `no answer from the API host (connection failed)`, `function key not found in Key Vault` on stage `keyvault`), and the error answer still carries `x-consignado-trace`. The proxy returns the status and the JSON of the API host unchanged, plus `x-consignado-host` and `x-consignado-trace` (base64url JSON `RequestTrace`). `cold` is true only for the Functions host and only when the upstream call took more than 8 s; the AKS host never reports `cold`.

### Version checks (`ETag`, `If-Match`)

The API host gives each contract a version (ADR 0003 of `consignado-api`): `GET api/v1/contracts/{id}` and each state change answer with `ETag: "<version>"`, and a state change with a stale `If-Match` gets 412 before anything changes.

- Request: the proxy forwards `If-Match` and `If-None-Match` to both API hosts.
- Response: the proxy passes on 2 upstream headers only (an allow-list, not a pass-through). `ETag`, when it is a valid entity tag (`"..."` or `W/"..."`, at most 128 characters). `Location`, when it points at an `api/v1/` path on the API host that was called: the gateway rewrites it to its own public path for that host (`/api/fn/api/v1/contracts/{id}` or `/api/aks/api/v1/contracts/{id}`). A `Location` on another host, outside `api/v1/`, or malformed, is dropped. `Access-Control-Expose-Headers` names `etag` and `location`. `Cache-Control: no-store` stays on every answer, so an `ETag` never makes an API answer cacheable.
- 412 (stale version) and 409 (state conflict, `concurrent-update`) come back with the JSON of the API and the trace, like any other upstream answer.
- **The gateway requires `If-Match`** on the 5 contract state changes: `POST api/v1/contracts/{id}/approve|reject|activate|settle|cancel`, both hosts. Without it, or with `If-Match: *` (which matches any version), the answer is 428 `precondition-required` and the API host is not called.

Why the gateway requires it and the API does not: the API is also called directly (tests, its own README examples), and an optional header there keeps those users working; it still protects a lost race with 409 at flush. Every client of the platform passes the gateway, and the only client is the frontend, which always sends the ETag of its last read. A required header turns "the client can ask for protection" into "every state change through the platform is protected". The cost is small: one more request (a read of the contract) when a client has no ETag. Deploy order: the edge Worker and the frontend first (they then send `If-Match`; the old gateway ignores it), the gateway last. A gateway deployed first answers 428 to every state change of the old frontend.

### Request trace (`x-consignado-trace`)

`RequestTrace = {host, cold, totalMs, events[], api?}`. Every event has `ts`, `stage`, `status`, `detail` and `ms` when the stage has its own time. **The `ms` values of a trace add up to `totalMs`** (a missing `ms` counts as 0; tested over all hosts, guests, failures and header cases).

| Host | Stages, in order |
|---|---|
| `functions` | `request`, `edge`, `gateway`, `identity`, `keyvault`, [`function-cold-start`, `jvm-start` only when cold], `functions-host`, `app`, [`database`], `response` |
| `aks` | `request`, `edge`, `gateway`, `identity`, `quota`, `keyvault`, `load-balancer`, `app`, [`database`], `response` |

| Stage | `ms` |
|---|---|
| `gateway` | total minus identity, Key Vault and upstream time (the gateway's own work) |
| `identity` | token check. **Left out for a guest** (no token), not shown as 0 ms |
| `keyvault` | Functions: the function key read; `0` with detail `cached` when no Key Vault read happened. AKS: `0`, "not needed for this host" |
| `function-cold-start` | only when cold (upstream above 8 s): upstream time minus `appMs` (the whole upstream time without the API header); `jvm-start` and `functions-host` then have no own `ms` |
| `functions-host` / `load-balancer` | upstream time minus `appMs`, never below 0: network, Functions host or internal load balancer, and the way back |
| `app` | `appMs - db.ms`; detail `Spring Boot handled the request in <instance>` |
| `database` | only when `db.statements > 0`: `db.ms`; detail `3 statements: 2 select, 1 insert` (kinds with 0 left out) |
| `response` | no `ms` when `app` is present; the upstream time when the API sent no valid header (as before addendum 2) |

`api` is the parsed `X-Consignado-Timing` response header of the API host (`{instance, appMs, db: {ms, statements, select, insert, update, delete, other, connectionMs, transactions} | null}`), absent when the header is missing or not valid. Then the trace has no `functions-host` (Functions), no `app`, no `database`, and `response` carries the upstream time.

Validation of `X-Consignado-Timing` (input from another service; on any doubt it is ignored and the trace is built without it): at most 1024 characters; a JSON object; `instance` 1 to 80 characters of `[A-Za-z0-9._-]`; `appMs`, `db.ms`, `db.connectionMs` numbers from 0 to 60000; the counts whole numbers from 0 to 10000; `select + insert + update + delete + other = statements`; `db.ms <= appMs + 1`; `appMs <= upstream time + 2 ms`; unknown fields are dropped. The header is not used for an upstream error (5xx, timeout, no connection): then the host stage (`functions-host` or `load-balancer`) has `status: "error"`, the short detail and the upstream time, `response` is `error` too, and there is no `app` or `database`.

The gateway reads only `Content-Type` and `X-Consignado-Timing` from the upstream answer. No upstream header (also not `X-Consignado-Timing` or `Server-Timing`) goes to the browser; the browser gets the data only inside `x-consignado-trace`.

## Configuration (environment variables, names only)

Required: `AZURE_CLIENT_ID`, `ENTRA_TENANT_ID`, `ENTRA_API_AUDIENCE`, `ADMIN_OID`, `AKS_RESOURCE_ID`, `AKS_API_SERVER_URL`, `AKS_CA_CERT_B64`, `AKS_INTERNAL_URL`, `APIFN_BASE_URL`, `KEY_VAULT_URL`, `STORAGE_ACCOUNT`, `OWNER_EMAIL`, `WINDOW_MINUTES`, `WINDOWS_MAX`, `COOLDOWN_MINUTES`, `BUDGET_LIMIT_USD`.

Optional, status page (a missing id makes that service `unknown` with detail `not configured`): `GATEWAY_APP_ID`, `API_APP_ID`, `KEY_VAULT_ID`, `POSTGRES_ID`, `APPINSIGHTS_ID` (without it: no `requests`, `errors`, `latency-avg`) (full ARM resource ids; the cluster id is `AKS_RESOURCE_ID`).

Optional: `ACS_ENDPOINT` and `EMAIL_SENDER` (or `ACS_SENDER`) (email; without them the mailer writes only an audit row), `ALLOW_DEV_ORIGIN=true` (CORS for `http://localhost:5173`), `EDGE` (the edge key is required unless `EDGE=local`; a missing value or any other value keeps the check on), `DAILY_WINDOWS_MAX` (default 12), `ENTRA_SPA_CLIENT_ID` (when set, a token whose `azp` is another client application is refused), `TURNSTILE_HOSTNAMES` (comma list; default `consignado.kmads.dev,cluster.consignado.kmads.dev`).

A missing or wrong variable stops the app at the first request. The log shows the name of the variable and never a value.

Key Vault secrets (names): `apifn-host-key` (function key of the API host), `edge-key` (shared with the Cloudflare Worker), `turnstile-secret` (optional), `hash-key` (made by Terraform; the gateway only reads Key Vault and fails with a clear log line when it is missing).

Tables (made by Terraform): `users`, `sessions`, `engine`, `contacts`, `audit`. Blob: container `data`, blob `snapshot.json`.

## Build and tests

```bash
cd gateway
./mvnw -B -ntp verify     # compile, 141 tests (JUnit 5, no network), package
ls target/azure-functions/func-consignado-gw-km01   # the staged function app
```

A JDK 21 or newer is necessary. The tests run the real core and the real handlers against in-memory fakes of every port. The coverage report is in `target/site/jacoco/index.html` (core and http: about 96% of the lines).

## Engine rules

- `POST /api/sessions` reads the ARM power state. A stopped cluster is started. A running cluster is not started again.
- The lifecycle events come from real signals: ARM state, Kubernetes nodes, pod conditions and container states, Service endpoints, then one real `GET /actuator/health/readiness` through the internal load balancer. The window opens only after that call answers 200.
- A window is counted when it opens. A start that does not reach ready in 15 minutes becomes `failed`, costs the user nothing, and the cluster is stopped.
- With no active window, the timer stops the cluster. Then the state is `cooldown` for `COOLDOWN_MINUTES`.
- A cluster that something else started (for example the deploy job) gets 20 minutes before the timer stops it.
- `paused` by the admin: new windows are refused, running windows end normally. `GET /api/engine` then shows:

  | Situation | `state` | `paused` | `pausedReason` |
  |---|---|---|---|
  | not paused | `stopped`, `starting`, `running`, `stopping` or `cooldown` | `false` | `null` |
  | paused, a window still runs (or starts) | `running` (or `starting`) | `true` | `admin` or `budget` |
  | paused, no window, cluster stopped or in cooldown | `paused` | `true` | `admin` or `budget` |
- Stage durations of a start: when the pod was made after the window was requested, the pod stages use the pod's own times: `secrets-mount` = `PodScheduled` -> `PodReadyToStartContainers` (or `Initialized`); `image-pull` = the pod events `Pulling` -> `Pulled` (0 ms and "image already on the node" when the event says "already present on machine"; "pull time not readable" when the events cannot be read); `container-start` = `Pulled` (or volumes ready) -> `containerStatuses[].state.running.startedAt`; `database` = `startedAt` -> `Ready` (or `ContainersReady`); `node` ends at `PodScheduled`. A pod older than the request (warm cluster) gives no pod durations. The budget hook also ends the running windows and stops the cluster at once.
- The gateway can only read, start and stop the cluster. `ArmClusterControl` has no code for a delete or an update.
- The engine row is written with its ETag (`If-Match`), like the users and sessions rows. The gateway can run 2 instances, and the timer, the admin, the budget webhook and the session polls all write this row. Each writer changes only its own fields on the newest row: on a conflict it reads the row again and applies its change again (at most 5 times; then 409 `busy` for the admin, a log line for the timer). The timer never sets `paused` or `pausedReason`, so a pause written while a timer run waits for ARM is never lost.
- The slow ARM calls are outside the read-modify-write. The timer first reads ARM and the session rows, then reads and writes the engine row, and only then sends a start or a stop: the conditional write is the claim. So two timer runs never both send a stop (a second run sees `stopping` from less than 2 minutes ago and waits for ARM) or both send a start again (`lastStartAt` less than 30 s ago). A refused stop gives the claim back, and the next tick tries again. The daily retention job uses the same claim (`lastRetentionAt`), and the 30-minute budget check (`lastBudgetCheckAt`).

## Status page (`GET /api/status`)

Built per host and cached 15 seconds in the instance. All sources run in parallel; each call has a 3 s timeout and each source a 3.5 s limit; a failed or late source makes only its service `unknown`, the route still answers 200. Azure Monitor numbers cover the last 5 minutes and are about 1 to 3 minutes behind.

Order: `functions`: edge, gateway, identity, keyvault, functions, database. `aks`: edge, gateway, identity, keyvault, load-balancer, cluster, node, pods, database.

| Service | Sources | State rule |
|---|---|---|
| edge | none (fixed text) | `up`: the request came through the Worker |
| gateway, functions | ARM `properties.state`, resource health, metrics | Running = up, Stopped = stopped; health Degraded/Unavailable makes it degraded/down (the gateway is always at least up: it answers) |
| identity | the gateway's Entra ID signing keys (loaded if the cache is empty) | up with "signing keys fetched N min ago"; unknown when they cannot be fetched |
| keyvault | metrics, resource health, ARM | Availability >= 99.9 % up, > 0 degraded, 0 down |
| database | ARM `properties.state`, metrics (`is_db_alive` for the state only) | Ready = up, Stopped = stopped, Starting/Updating = starting; `is_db_alive` 0 = down |
| cluster | ARM power state (existing role) | Running = up, Starting = starting, else stopped |
| load-balancer | the gateway's own `GET /actuator/health/readiness` to 10.10.3.200, metric `latency-avg` "now" | 200 = up, else down; not called when the cluster is not running |
| node, pods | Kubernetes API (existing reader role: pods, endpoints, `apps/deployments`, `horizontalpodautoscalers`), node metrics | all Ready = up, some = degraded, none = down; pods detail: ready count, restarts, image tag, age of the newest pod, Deployment ready/desired replicas (fewer ready than desired = degraded), HPA current/desired (min to max); `replicas` = the Deployment's ready replicas, or the ready pods when the Deployment cannot be read; no call when the cluster is not running |

`build` (optional on a `ServiceStatus`): `{commit, repository}`. The `pods` service carries it when the image tag of the API container (`consignado-api`) in the newest Ready pod (any live pod when none is Ready) is a git commit (7 to 40 lower-case hex characters) of `kmadsdev/consignado-api`. A tag like `latest` gives no `build`. The `functions` service has no `build`: no data the gateway reads holds the commit of the Functions host.

Metric table (names checked with `az monitor metrics list-definitions` on 2026-11-17; the code table is `core/StatusMetrics.java`):

| Service | Azure metric | Aggregation over 5 x 1 min | key | unit |
|---|---|---|---|---|
| gateway, functions | Application Insights `appi-consignado` (`APPINSIGHTS_ID`): `requests/count`, split `cloud/roleName` = app name | sum of Count | `requests` | count |
| gateway, functions | `requests/failed`, same split (no series for a role = 0 failed) | sum of Count | `errors` | count |
| gateway, functions | `requests/duration`, same split (only Average, Minimum, Maximum exist: no p95) | minute averages weighted by their counts | `latency-avg` | ms |
| gateway, functions | `OnDemandFunctionExecutionCount` | sum of Total | `executions` | count |
| gateway, functions | `InstanceCount` | mean of Average | `instances` | count |
| gateway, functions | `CpuPercentage` | mean of Average | `cpu` | % |
| gateway, functions | `MemoryWorkingSet` | mean of Average / 1 MiB | `memory` | MB |
| keyvault | `ServiceApiHit` | sum of Total | `requests` | count |
| keyvault | `ServiceApiLatency` | mean of Average | `latency-avg` | ms |
| keyvault | `Availability` | mean of Average | `availability` | % |
| database | `cpu_percent`, `memory_percent` | mean of Average | `cpu`, `memory` | % |
| database | `active_connections` | mean of Average | `connections` | count |
| database | `is_db_alive` | Maximum of the newest point | (state only) | - |
| node | `node_cpu_usage_percentage`, `node_memory_working_set_percentage` (on `aks-consignado`) | mean of Average | `cpu`, `memory` | % |
| pods | Kubernetes (not Azure Monitor) | now | `replicas` (ready pods), `restarts` | count |

The function apps themselves expose no request count, HTTP 5xx or response time as platform metrics; those three come from the Application Insights component (`APPINSIGHTS_ID`, one call for both apps). Without `APPINSIGHTS_ID` they are left out (no invented number). A metric without a data point in the window has `value: null`.

Needs: role `Monitoring Reader` on `rg-consignado` for `id-consignado-gw`; resource provider `Microsoft.ResourceHealth` registered in the subscription (it was `NotRegistered` on 2026-11-17; until then the detail says "resource health: not readable").

## Personal data: retention and deletion

- **Retention (timer, at most once per day; the time of the last run is `lastRetentionAt` in the engine row):**
  - `audit` rows of a day older than 90 days are deleted (the partition key is the day).
  - An account with no activity for 12 months (`lastSeenAt`, or `createdAt` when `lastSeenAt` is empty) is deleted: its `users` row, its `sessions` rows, and its `contacts` rows (same email or same `userId`). One audit row per account: `who: system`, `action: retention-delete`, `target: <HMAC-SHA256 of the user id>`. The owner account and an account with an active window are kept.
  - The time is written before the job runs: a failed job runs again the next day, not each minute. Each run writes one log line `retention: N audit rows deleted, M inactive accounts deleted`.
- **Deletion on request (LGPD article 18):** `POST /api/admin/users/{id}` `{"action":"delete"}` (admin only; `DELETE` is blocked at the edge by design). It deletes the same rows as above and writes one audit row `admin.user.delete` with the hash as the target. Answer: `{"deleted": true, "entraAccount": "delete it in the Entra admin center"}`: the gateway has no directory rights, so the owner deletes the Entra ID guest account by hand. The admin cannot delete his own account (409 `self-delete`). An open window of the user ends with its row; the timer then stops the cluster.
- Audit rows written before a deletion keep the user id as `who`/`target` until the 90-day retention removes them (security log).

## Owner mail

`Mailers.acs` sends with the managed identity (role `Communication and Email Service Owner` on `acs-consignado-km01`) from `EMAIL_SENDER` (the `DoNotReply` address of the Azure-managed domain) to `OWNER_EMAIL`. `GuardedMailer` wraps it: every send leaves one log line (`mail to the owner accepted by ACS: <subject>` or `mail to the owner failed: <subject>: <error class and message, addresses removed>`) and one audit row (`mail.sent` / `mail.failed`). A failed mail never fails the request. The ACS client is built at the first mail. Delivery is visible in the ACS metric `DeliveryStatusUpdate`.

## Security decisions and their trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| The gateway verifies the token itself (signature against the tenant JWKS, issuer, tenant, audience, `exp`, `nbf`, scope `access_as_user`). | It does not depend on a platform feature and the rules are tested. | The JWKS is cached for 1 hour: a key that Entra ID removes stays accepted for that time at most. |
| A token that does not verify gives 401. It is never treated as a guest. | A broken client must see its error. | None. |
| Edge key: unless `EDGE=local`, every route except the budget hook needs `X-Edge-Key` equal to the secret `edge-key` (constant-time compare). A wrong or missing header is 403 with no detail; a secret that cannot be read (Key Vault error or no value) is 503 `unavailable`, never a pass. | Only traffic that passed the Cloudflare edge (WAF, rate limit) reaches the code. | One shared secret. A change of the secret needs the Worker and Key Vault to change together (the cache is 5 minutes). |
| The client IP comes only from `CF-Connecting-IP`, and only after the edge key matched. | A client cannot forge its address. | Without the edge (local runs) there is no IP. |
| IP and fingerprint are stored only as HMAC-SHA256 with a Key Vault key. | A leak of the table does not show addresses. | The duplicate check sees only exact matches. A new browser or a VPN passes it. It is a filter, not a proof. |
| Same device (flag `same-device`) or same normalised mailbox (flag `same-mailbox`: lower case, no `+tag`, no dots for gmail.com and googlemail.com; stored only as HMAC `mailboxHash`) holds the account; same IP only sets the flag `same-ip`. Other flag: `disposable-email`. | An office or a university shares one IP. The fingerprint comes from the client, the mailbox from the verified token. | The owner must approve held accounts by hand. |
| The proxy forwards only `api/v1/` paths, methods GET, POST and PATCH, bodies up to 64 KB, and seven request headers (`accept`, `accept-language`, `content-type`, `idempotency-key`, `if-match`, `if-none-match`, `x-request-id`). `Authorization`, cookies and hop-by-hop headers never go upstream. Only 2 response headers of the API go on: `ETag` and a rewritten `Location` (see "Version checks"). | An allow-list is safer than a block-list. | A new header that the API needs must be added here. |
| A guest can call one route: `POST api/v1/simulations/preview` on the Functions host. | It has no database call. | None. |
| Agreements: only the owner may write them (any method except GET on `api/v1/agreements` and below, both hosts); other users get 403 `forbidden`. | Agreement names are free text and the public snapshot shows them unmasked. | Agreement names in the snapshot come only from the owner's seed data. |
| Users, sessions and engine rows are written with the ETag (`If-Match`); a conflict reads the row again and applies the change once more (engine row: up to 5 times). | A request that read the row earlier never overwrites an admin `block`, `hold` or `grant`. | A second conflict gives 409 `busy`. |
| Errors are generic `{code, message}`. The log holds exception class names, not messages. | A message can hold a host name or a value. | Debugging needs Application Insights and the class name. |
| Secrets are cached 5 minutes in memory. | Fewer Key Vault calls, and the trace shows `cached`. | A rotated secret is used after 5 minutes at most. |
| Turnstile fails closed: no secret, a Key Vault error or no answer from Cloudflare = refuse. The `hostname` of the answer must be in `TURNSTILE_HOSTNAMES`. | A deleted secret must not turn the bot check off. | The forms need the secret `turnstile-secret`. |
| Per-user guard in memory (5 session requests and 3 contact requests in 10 minutes). | Stops a loop of one user. | Each instance counts alone. The real rate limit is the edge. |
| Every admin call, every start and every stop writes an audit row. An audit failure does not stop the request. | The service stays up. | A storage failure can lose audit rows (it is logged). |
| The snapshot masks person names and CPF and removes email, phone and address. | `GET /api/snapshot` is public. | Salary values stay, without a readable name or CPF. |
