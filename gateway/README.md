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
| `POST /api/hooks/budget` | function key (no edge key) | spend at or above the budget, or a body that cannot be read: pause (reason `budget`), end the open windows, stop the cluster. An earlier alert writes an audit row only |
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
./mvnw -B -ntp verify     # compile, 82 tests (JUnit 5, no network), package
ls target/azure-functions/func-consignado-gw-km01   # the staged function app
```

A JDK 21 or newer is necessary. The tests run the real core and the real handlers against in-memory fakes of every port. The coverage report is in `target/site/jacoco/index.html` (core and http: about 96% of the lines).

## Deploy and host settings

- `.github/workflows/gateway.yml` builds, tests and deploys on a push to `main` (GitHub OIDC, no stored password).
- The app settings belong to Terraform (`consignado-api/infra/terraform/functions.tf`). A setting made with the portal or the CLI is removed by the next apply.
- Host storage uses the identity: `AzureWebJobsStorage__accountName`, `__credential=managedidentity`, `__clientId`. The setting `AzureWebJobsStorage` itself is empty: the Terraform provider adds it with an account key, the storage account refuses keys, and the host then failed with 403.
- The Functions host refuses a route that starts with `admin` (a built-in route). The admin routes use the route template `{area:regex(^admin$)}/{*path}`; the public URL is still `/api/admin/...`.
- The budget action group calls `https://consignado.kmads.dev/api/hooks/budget?code=<function key>`. The URL is passed to Terraform as `TF_VAR_budget_webhook_url` and is not in any file: a `terraform apply` without that variable removes the webhook.

## Live state (2026-11-17)

Verified live through the edge: health, engine, snapshot, `me` (401 with no token, then a real sign-up), the guest preview, all operations on both hosts, the access rules (admin routes 403 for a user, the AKS host 403 with no window), one access window on a running cluster, and the automatic stop (ARM stop accepted, engine `stopping` then `cooldown`, cluster `Stopped`).

Measured in the browser: serverless host cold 18.6 to 18.9 s, warm 0.25 to 0.9 s; Kubernetes host warm about 0.76 s.

Not verified at the time of writing: a real cold start from a stopped cluster (ARM `start` uses the same request as `stop` and is unit-tested only), the admin pages with a real token, the email to the owner, the duplicate-account hold with two real accounts, the budget pause from a real alert.

## Engine rules

- `POST /api/sessions` reads the ARM power state. A stopped cluster is started. A running cluster is not started again.
- The lifecycle events come from real signals: ARM power and provisioning state, pod conditions and container states, Service endpoints, then one real `GET /actuator/health/readiness` through the internal load balancer. The window opens only after that call answers 200.
- The node state comes from the pods: a pod with a running container proves that its node is Ready. The role `Azure Kubernetes Service RBAC Reader` cannot list nodes (they are cluster-scoped), so `GET /api/v1/nodes` answers 403; that answer is expected and is not an error. With a role that can read nodes, the code uses the node list with no change.
- ARM `start` and `stop` are `POST` calls over HTTP/1.1 with an empty body, so the client sends `Content-Length: 0`. Over HTTP/2 the Java client sends no `Content-Length`, and ARM answered 411 "Length Required".
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
| Errors to the caller are generic `{code, message}`. The log line holds the exception class and its message, cut at 300 characters. | The caller never sees an internal name. The log must show the cause (the Functions log handler does not fill `{0}` placeholders, so every line is built as text). | A message in the log can hold a host name. No secret value is logged. |
| Secrets are cached 5 minutes in memory. | Fewer Key Vault calls, and the trace shows `cached`. | A rotated secret is used after 5 minutes at most. |
| Turnstile: no secret in Key Vault = accept and log `bot check not configured`. Cloudflare does not answer = refuse. | The platform can start before the Turnstile widget exists. | Until the secret exists there is no bot check. |
| Per-user guard in memory (5 session requests and 3 contact requests in 10 minutes). | Stops a loop of one user. | Each instance counts alone. The real rate limit is the edge. |
| Every admin call, every start and every stop writes an audit row. An audit failure does not stop the request. | The service stays up. | A storage failure can lose audit rows (it is logged). |
| The snapshot masks person names and CPF and removes email, phone and address. | `GET /api/snapshot` is public. | Salary values stay, without a readable name or CPF. |
