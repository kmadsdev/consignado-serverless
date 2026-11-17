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
| `POST /api/contact` | all + Turnstile token | `ContactRequest` (201), email to the owner. Kinds `contact` and `more-windows`. A signed-in sender is linked: the row has `userId` (null for a guest), and `GET /api/admin/contacts` shows it |
| `GET /api/me` | token | `Me`; the first call creates the user and emails the owner |
| `PUT /api/me/profile` | token + Turnstile token | `Profile` + `{fingerprint, turnstileToken}`; duplicate check; `Me` |
| `POST /api/sessions` | user | `Session` (201; 200 when the user already has an active window). 409 `paused`, `held`, `blocked`, `no-windows`, `profile-incomplete`, `cooldown` (with `Retry-After`), `daily-limit` (`DAILY_WINDOWS_MAX`, default 12, new windows in any 24 hours over all accounts; the owner is not limited), `busy` (the account row changed twice at the same time) |
| `GET /api/sessions/{id}` | owner or admin | `Session` with events. A poll also reads the real cluster signals (at most every 3 s) |
| `POST /api/sessions/{id}/end` | owner or admin | ends the window early: `Session` in state `ended` (200; an ended or failed session comes back unchanged). The window is not given back to the quota (a `starting` window is counted now). The timer then stops the cluster when no window is active. 404 for another user's session |
| `GET, POST, PATCH /api/fn/{*path}` | token; guest only `POST api/v1/simulations/preview` (503 `paused` while the engine is paused for budget) | proxy to the Functions API host |
| `GET, POST, PATCH /api/aks/{*path}` | token + own window in state `ready` | proxy to the AKS host |
| `GET /api/admin/users`, `POST /api/admin/users/{id}` | admin | `AdminUser[]` / `AdminUser`; actions `approve`, `hold`, `block`, `grant` (`windows` 1 to 20, default 1, added to the maximum), `delete` (see "Personal data") |
| `GET /api/admin/sessions`, `/state`, `/contacts` | admin | `Session[]`, `AdminState`, `ContactRequest[]` |
| `POST /api/admin/pause` `{paused}`, `POST /api/admin/engine/stop` | admin | `AdminState` |
| `POST /api/hooks/budget` | function key | spend at or above the budget: pauses (reason `budget`) and stops the cluster; spend below: audit only; a body without numeric `data.SpendingAmount` and `data.Budget`: 400, no pause |
| timer `reaper`, every minute | - | advances sessions, ends windows, stops the idle cluster, checks the month-to-date cost every 30 minutes (at or above `BUDGET_LIMIT_USD`: same pause as the budget hook), refreshes the snapshot every 10 minutes, runs the retention job once per day |

Errors are `{code, message}`. The proxy returns the status and the JSON of the API host unchanged, plus `x-consignado-host` and `x-consignado-trace` (base64url JSON `RequestTrace`). `cold` is true only for the Functions host and only when the upstream call took more than 8 s; the AKS host never reports `cold`.

## Configuration (environment variables, names only)

Required: `AZURE_CLIENT_ID`, `ENTRA_TENANT_ID`, `ENTRA_API_AUDIENCE`, `ADMIN_OID`, `AKS_RESOURCE_ID`, `AKS_API_SERVER_URL`, `AKS_CA_CERT_B64`, `AKS_INTERNAL_URL`, `APIFN_BASE_URL`, `KEY_VAULT_URL`, `STORAGE_ACCOUNT`, `OWNER_EMAIL`, `WINDOW_MINUTES`, `WINDOWS_MAX`, `COOLDOWN_MINUTES`, `BUDGET_LIMIT_USD`.

Optional: `ACS_ENDPOINT` and `EMAIL_SENDER` (or `ACS_SENDER`) (email; without them the mailer writes only an audit row), `ALLOW_DEV_ORIGIN=true` (CORS for `http://localhost:5173`), `EDGE` (the edge key is required unless `EDGE=local`; a missing value or any other value keeps the check on), `DAILY_WINDOWS_MAX` (default 12), `ENTRA_SPA_CLIENT_ID` (when set, a token whose `azp` is another client application is refused), `TURNSTILE_HOSTNAMES` (comma list; default `consignado.kmads.dev,cluster.consignado.kmads.dev`).

A missing or wrong variable stops the app at the first request. The log shows the name of the variable and never a value.

Key Vault secrets (names): `apifn-host-key` (function key of the API host), `edge-key` (shared with the Cloudflare Worker), `turnstile-secret` (optional), `hash-key` (made by Terraform; the gateway only reads Key Vault and fails with a clear log line when it is missing).

Tables (made by Terraform): `users`, `sessions`, `engine`, `contacts`, `audit`. Blob: container `data`, blob `snapshot.json`.

## Build and tests

```bash
cd gateway
./mvnw -B -ntp verify     # compile, 100 tests (JUnit 5, no network), package
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
| The proxy forwards only `api/v1/` paths, methods GET, POST and PATCH, bodies up to 64 KB, and six request headers (`accept`, `accept-language`, `content-type`, `idempotency-key`, `if-none-match`, `x-request-id`). `Authorization`, cookies and hop-by-hop headers never go upstream. | An allow-list is safer than a block-list. | A new header that the API needs must be added here. |
| A guest can call one route: `POST api/v1/simulations/preview` on the Functions host. | It has no database call. | None. |
| Agreements: only the owner may write them (any method except GET on `api/v1/agreements` and below, both hosts); other users get 403 `forbidden`. | Agreement names are free text and the public snapshot shows them unmasked. | Agreement names in the snapshot come only from the owner's seed data. |
| Users rows are written with the ETag (`If-Match`); a conflict reads the row again and applies the change once more. | A request that read the row earlier never overwrites an admin `block`, `hold` or `grant`. | A second conflict gives 409 `busy`. |
| Errors are generic `{code, message}`. The log holds exception class names, not messages. | A message can hold a host name or a value. | Debugging needs Application Insights and the class name. |
| Secrets are cached 5 minutes in memory. | Fewer Key Vault calls, and the trace shows `cached`. | A rotated secret is used after 5 minutes at most. |
| Turnstile fails closed: no secret, a Key Vault error or no answer from Cloudflare = refuse. The `hostname` of the answer must be in `TURNSTILE_HOSTNAMES`. | A deleted secret must not turn the bot check off. | The forms need the secret `turnstile-secret`. |
| Per-user guard in memory (5 session requests and 3 contact requests in 10 minutes). | Stops a loop of one user. | Each instance counts alone. The real rate limit is the edge. |
| Every admin call, every start and every stop writes an audit row. An audit failure does not stop the request. | The service stays up. | A storage failure can lose audit rows (it is logged). |
| The snapshot masks person names and CPF and removes email, phone and address. | `GET /api/snapshot` is public. | Salary values stay, without a readable name or CPF. |
