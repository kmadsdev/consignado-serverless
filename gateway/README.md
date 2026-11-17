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
| `GET /api/engine` | all | `Engine` |
| `GET /api/snapshot` | all | masked copy of shared data `{asOf, agreements, customers, contracts}` |
| `POST /api/contact` | all + Turnstile token | `ContactRequest` (201), email to the owner |
| `GET /api/me` | token | `Me`; the first call creates the user and emails the owner |
| `PUT /api/me/profile` | token + Turnstile token | `Profile` + `{fingerprint, turnstileToken}`; duplicate check; `Me` |
| `POST /api/sessions` | user | `Session` (201; 200 when the user already has an active window). 409 `paused`, `held`, `blocked`, `no-windows`, `profile-incomplete`, `cooldown` (with `Retry-After`) |
| `GET /api/sessions/{id}` | owner or admin | `Session` with events. A poll also reads the real cluster signals (at most every 3 s) |
| `GET, POST, PATCH /api/fn/{*path}` | token; guest only `POST api/v1/simulations/preview` | proxy to the Functions API host |
| `GET, POST, PATCH /api/aks/{*path}` | token + own window in state `ready` | proxy to the AKS host |
| `GET /api/admin/users`, `POST /api/admin/users/{id}` | admin | `AdminUser[]` / `AdminUser`; actions `approve`, `hold`, `block`, `grant` (`windows` 1 to 20, default 1, added to the maximum) |
| `GET /api/admin/sessions`, `/state`, `/contacts` | admin | `Session[]`, `AdminState`, `ContactRequest[]` |
| `POST /api/admin/pause` `{paused}`, `POST /api/admin/engine/stop` | admin | `AdminState` |
| `POST /api/hooks/budget` | function key | pauses (reason `budget`) and stops the cluster |
| timer `reaper`, every minute | - | advances sessions, ends windows, stops the idle cluster, refreshes the snapshot every 10 minutes |

Errors are `{code, message}`. The proxy returns the status and the JSON of the API host unchanged, plus `x-consignado-host` and `x-consignado-trace` (base64url JSON `RequestTrace`). `cold` is true only for the Functions host and only when the upstream call took more than 8 s; the AKS host never reports `cold`.

## Configuration (environment variables, names only)

Required: `AZURE_CLIENT_ID`, `ENTRA_TENANT_ID`, `ENTRA_API_AUDIENCE`, `ADMIN_OID`, `AKS_RESOURCE_ID`, `AKS_API_SERVER_URL`, `AKS_CA_CERT_B64`, `AKS_INTERNAL_URL`, `APIFN_BASE_URL`, `KEY_VAULT_URL`, `STORAGE_ACCOUNT`, `OWNER_EMAIL`, `WINDOW_MINUTES`, `WINDOWS_MAX`, `COOLDOWN_MINUTES`, `BUDGET_LIMIT_USD`.

Optional: `ACS_ENDPOINT` and `EMAIL_SENDER` (or `ACS_SENDER`) (email; without them the mailer writes only an audit row), `ALLOW_DEV_ORIGIN=true` (CORS for `http://localhost:5173`), `EDGE=cloudflare` (the edge key becomes mandatory).

A missing or wrong variable stops the app at the first request. The log shows the name of the variable and never a value.

Key Vault secrets (names): `apifn-host-key` (function key of the API host), `edge-key` (shared with the Cloudflare Worker), `turnstile-secret` (optional), `hash-key` (made by Terraform; the gateway only reads Key Vault and fails with a clear log line when it is missing).

Tables (made by Terraform): `users`, `sessions`, `engine`, `contacts`, `audit`. Blob: container `data`, blob `snapshot.json`.

## Build and tests

```bash
cd gateway
./mvnw -B -ntp verify     # compile, 77 tests (JUnit 5, no network), package
ls target/azure-functions/func-consignado-gw-km01   # the staged function app
```

A JDK 21 or newer is necessary. The tests run the real core and the real handlers against in-memory fakes of every port. The coverage report is in `target/site/jacoco/index.html` (core and http: about 96% of the lines).

## Engine rules

- `POST /api/sessions` reads the ARM power state. A stopped cluster is started. A running cluster is not started again.
- The lifecycle events come from real signals: ARM state, Kubernetes nodes, pod conditions and container states, Service endpoints, then one real `GET /actuator/health/readiness` through the internal load balancer. The window opens only after that call answers 200.
- A window is counted when it opens. A start that does not reach ready in 15 minutes becomes `failed`, costs the user nothing, and the cluster is stopped.
- With no active window, the timer stops the cluster. Then the state is `cooldown` for `COOLDOWN_MINUTES`.
- A cluster that something else started (for example the deploy job) gets 20 minutes before the timer stops it.
- `paused` by the admin: new windows are refused, running windows end normally. The budget hook also ends the running windows and stops the cluster at once.
- The gateway can only read, start and stop the cluster. `ArmClusterControl` has no code for a delete or an update.

## Security decisions and their trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| The gateway verifies the token itself (signature against the tenant JWKS, issuer, tenant, audience, `exp`, `nbf`, scope `access_as_user`). | It does not depend on a platform feature and the rules are tested. | The JWKS is cached for 1 hour: a key that Entra ID removes stays accepted for that time at most. |
| A token that does not verify gives 401. It is never treated as a guest. | A broken client must see its error. | None. |
| Edge key: with `EDGE=cloudflare`, every route except the budget hook needs `X-Edge-Key` equal to the secret `edge-key` (constant-time compare). A refusal is 403 with no detail. | Only traffic that passed the Cloudflare edge (WAF, rate limit) reaches the code. | One shared secret. A change of the secret needs the Worker and Key Vault to change together (the cache is 5 minutes). |
| The client IP comes only from `CF-Connecting-IP`, and only after the edge key matched. | A client cannot forge its address. | Without the edge (local runs) there is no IP. |
| IP and fingerprint are stored only as HMAC-SHA256 with a Key Vault key. | A leak of the table does not show addresses. | The duplicate check sees only exact matches. A new browser or a VPN passes it. It is a filter, not a proof. |
| Same device holds the account; same IP only sets a flag. | An office or a university shares one IP. | The owner must approve held accounts by hand. |
| The proxy forwards only `api/v1/` paths, methods GET, POST and PATCH, bodies up to 64 KB, and six request headers (`accept`, `accept-language`, `content-type`, `idempotency-key`, `if-none-match`, `x-request-id`). `Authorization`, cookies and hop-by-hop headers never go upstream. | An allow-list is safer than a block-list. | A new header that the API needs must be added here. |
| A guest can call one route: `POST api/v1/simulations/preview` on the Functions host. | It has no database call. | None. |
| Errors are generic `{code, message}`. The log holds exception class names, not messages. | A message can hold a host name or a value. | Debugging needs Application Insights and the class name. |
| Secrets are cached 5 minutes in memory. | Fewer Key Vault calls, and the trace shows `cached`. | A rotated secret is used after 5 minutes at most. |
| Turnstile: no secret in Key Vault = accept and log `bot check not configured`. Cloudflare does not answer = refuse. | The platform can start before the Turnstile widget exists. | Until the secret exists there is no bot check. |
| Per-user guard in memory (5 session requests and 3 contact requests in 10 minutes). | Stops a loop of one user. | Each instance counts alone. The real rate limit is the edge. |
| Every admin call, every start and every stop writes an audit row. An audit failure does not stop the request. | The service stays up. | A storage failure can lose audit rows (it is logged). |
| The snapshot masks person names and CPF and removes email, phone and address. | `GET /api/snapshot` is public. | Salary values stay, without a readable name or CPF. |
