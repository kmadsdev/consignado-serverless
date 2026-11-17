# api-host: the Spring Boot API as an Azure Functions custom handler

This folder builds the "serverless host" of the consignado platform. It runs the **unchanged** API of
[`kmadsdev/consignado-api`](https://github.com/kmadsdev/consignado-api) (Java 21, Spring Boot 3.5) on the function app
`func-consignado-api-km01` (Flex Consumption, runtime `custom`, Linux x64, 2048 MB). The same build of the API also
runs on AKS, so the two hosts can be compared fairly.

## How it works

```
gateway (Java function, in snet-func)
   |  HTTPS + header x-functions-key (host key from Key Vault: apifn-host-key)
   v
Azure Functions host (func-consignado-api-km01)
   |  1. at instance start: runs  runtime/bin/java <flags> -Dserver.port=<FUNCTIONS_CUSTOMHANDLER_PORT> -jar app/consignado-api.jar
   |  2. function "proxy", route {*path}, methods GET POST PATCH, auth level "function"
   |  3. enableForwardingHttpRequest: the original request goes to http://127.0.0.1:<port>/<same path>
   v
Spring Boot (Tomcat) -> Flyway at start -> Hikari (3 connections) -> PostgreSQL psql-consignado-km01
```

The package (`dist/api-host.zip`):

| Path | What |
|---|---|
| `host.json` | `customHandler` (executable, arguments, forwarding), empty `routePrefix`, `functionTimeout` 2 minutes |
| `proxy/function.json` | the one HTTP trigger: route `{*path}`, `get`, `post`, `patch`, `authLevel: function` |
| `runtime/` | Java 21 runtime for Linux x64, made with `jlink` from Temurin 21 (about 87 MB) |
| `app/consignado-api.jar` + `app/lib/` | the API, in the Spring Boot `extract` layout (same classes and libraries as the fat jar) |
| `app/consignado-api.jsa` | class data sharing (CDS) archive of the application |
| `config/application.properties` | defaults of this host (pool size, relay off); an app setting wins over it |
| `build-info.json` | API commit, Java version, module list, build time |

No secret is in the package. The app settings give `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` (names only here).

## Build and prove

```bash
./build.sh /path/to/consignado-api      # or: ./build.sh --ref main   (clones the API at a branch, tag or SHA)
./prove-local.sh                        # Functions host container + PostgreSQL, checks and times, then removes all
```

`build.sh` needs only `bash`, `docker` and `zip`. Steps:
1. Maven wrapper in a `eclipse-temurin:21-jdk-noble` container: `./mvnw -B -ntp -DskipTests package`. The source is mounted read-only and copied, so nothing is written into the API clone.
2. `jdeps` finds the JDK modules that the jar uses. They are added to a fixed list for Spring Boot, Hibernate, PostgreSQL, Flyway and Jackson.
3. `jlink` makes the runtime in a `linux/amd64` container (`--strip-debug --compress zip-6 --generate-cds-archive`).
4. The same runtime extracts the jar (`-Djarmode=tools ... extract`) and does one training run that stops after the Spring context refresh (`-Dspring.context.exit=onRefresh`, no database necessary) to write the CDS archive. `CDS=0 ./build.sh ...` skips this and packages the fat jar as it is.
5. `zip` with the executable bits and the file times.

`prove-local.sh` unpacks the zip, starts `postgres:16-alpine` and `mcr.microsoft.com/azure-functions/base:4` (linux/amd64, 2 GB) on a private Docker network, and calls the API **through the Functions host** with a throwaway function key. It makes its keys with `openssl`, does not print them, and removes the containers, the network and its files at the end.

## JVM flags and settings

In `host.json` (arguments of the process):

| Flag | Why |
|---|---|
| `-XX:MaxRAMPercentage=70` | heap up to 70% of the 2048 MB instance; the rest is for the Functions host and native memory |
| `-XX:TieredStopAtLevel=1` | only the C1 compiler: faster start, lower peak speed. Correct for an instance that lives for minutes |
| `-XX:+UseSerialGC` | smallest footprint and start cost |
| `-Xshare:auto` + `-XX:SharedArchiveFile=app/consignado-api.jsa` | use the CDS archive; if the JVM refuses it, the start continues without it |
| `-XX:+ExitOnOutOfMemoryError` | stop fast; the Functions host starts a new process |
| `-Dserver.port=%FUNCTIONS_CUSTOMHANDLER_PORT%` | the port that the Functions host chooses |
| `-Dserver.shutdown=immediate` | the API default is `graceful`; a function instance gets no time for it |

In `config/application.properties` (Spring reads `./config/` in the working directory; an environment variable wins):
`spring.datasource.hikari.maximum-pool-size=3`, `minimum-idle=0`, `idle-timeout=30s`, `max-lifetime=5min`, Flyway on, and `consignado.outbox.relay.enabled=false`.

**Outbox relay: off on this host.** The relay is a poller (`@Scheduled`, each 2 s). A function instance is frozen between requests and removed when idle, so a poller there runs only by accident, keeps one database connection busy, and gives no delivery guarantee. The AKS host runs the relay and drains the same `outbox_event` table, because both hosts use the same database. An event written through the Functions host waits until an AKS window is open. If the events must leave without AKS, the correct design is a timer-trigger function, not a thread in this process.

## What the Microsoft documentation says, and what the proof showed

Sources: [custom handlers](https://learn.microsoft.com/azure/azure-functions/functions-custom-handlers), [HTTP trigger](https://learn.microsoft.com/azure/azure-functions/functions-bindings-http-webhook-trigger), [Flex Consumption how-to](https://learn.microsoft.com/azure/azure-functions/flex-consumption-how-to) (read 2026-11-17).

| Topic | Documentation | Seen in the local proof (host 4.636.2.2 in `azure-functions/base:4`) |
|---|---|---|
| Argument expansion | "Arguments support expansion of environment variables (application settings) by using `%%` notation." | `%FUNCTIONS_CUSTOMHANDLER_PORT%` became a real port (`Tomcat started on port 46153`) |
| Working directory | `workingDirectory` can change it | With `""` the directory is the package root: the relative paths `runtime/bin/java`, `app/...` and `config/` all worked |
| Forwarding | The page of 2026-10 names the setting `enableProxyingHttpRequest` (with response streaming). The path sent to the handler is the same as the original request | **This host image does not know `enableProxyingHttpRequest`**: it ignored it and sent the binding payload to `POST /proxy` (the API answered 404, the host 500). `enableForwardingHttpRequest` works, so `host.json` uses it. It also works on the live Flex Consumption app (2026-11-17) |
| Start limit | "The custom handler web server needs to start within 60 seconds." | Start took 6.4 s to 11.6 s under emulation. The host calls `GET /` on the handler to see if it is up |
| Route prefix | `extensions.http.routePrefix: ""` removes `/api` | The API paths stay `/api/v1/...` and `/actuator/...` |
| Reserved paths | "`/admin` and `/runtime` ... are reserved ... they never reach your functions". Do not use function routes that begin with `admin` or `runtime` | `/admin/host/status` and `/runtime/webhooks/x` did not reach Spring. The API has no such paths, so nothing is lost |
| Methods | Only the methods in `function.json` | `DELETE` gets 404 from the host and never reaches the API |
| Keys | `x-functions-key` header or `code` query parameter; any host key works | 401 without a key. The header also reaches the API, which ignores it |
| Limits | An HTTP function must answer in 230 s (load balancer) | `functionTimeout` is 2 minutes |
| "Not a reverse proxy" | Microsoft warns: "Azure Functions isn't a standard reverse proxy. Some components of the HTTP request, such as certain headers and routes, might be restricted", and names Container Apps or App Service for web apps | The `Host` header was kept (the `Location` of a 201 shows the caller's host). No header problem seen for this JSON API |
| Deployment | Flex Consumption: `az functionapp deployment source config-zip --src <zip> --name <app> --resource-group <rg>`; the zip goes to the deployment blob container. No remote build for a ready package The workflow `api-host.yml` deploys this way. Live since 2026-11-17 |

## Measured (2026-11-17)

Machine: Apple silicon Mac, Docker `linux/amd64` **under emulation**, container limit 2 GB, PostgreSQL in a container on the same network. Real x64 hardware is faster for the JVM, but a real cold start also has the cost of instance allocation and of the package mount, which this proof cannot show. The live numbers on Azure are in the next section.

| Measure | Fat jar, JDK CDS only | With the application CDS archive (the default build) |
|---|---|---|
| Cold start: container start to first 200 on `/actuator/health/readiness`, through the host | 16.3 s to 17.1 s (3 runs) | 11.2 s to 12.0 s (4 runs) |
| Spring Boot start, from its own log | 11.0 s to 11.6 s | 6.4 s to 6.7 s |
| Warm `GET /api/v1/contracts` (30 calls) | p50 12 ms to 13 ms | p50 13 ms |
| Warm `GET /actuator/health/readiness` (30 calls) | p50 8 ms to 9 ms | p50 8 ms to 10 ms |
| Java process memory (RSS) after the calls | 394 MB to 395 MB | 387 MB to 395 MB |
| Zip size | 113 MB | 129 MB |

The Functions host itself is ready in about 0.5 s; the rest of the cold start is the JVM, Flyway and Hibernate.

## Measured live on Azure (2026-11-17)

Measured in a browser through the whole path (Cloudflare edge, gateway, this function app), with the trace header of the gateway:

| Measure | Time |
|---|---|
| Cold call (2 of 2: a guest preview, and a signed-in `GET` 17 minutes after the last call) | 18.6 s to 18.9 s; stage `function-cold-start` 18.6 s |
| Warm guest preview | 0.25 s in the browser; trace `totalMs` 23 |
| Warm signed-in calls (11 calls) | 0.55 s to 1.2 s in the browser, median 0.9 s |

The live cold start is about 7 s longer than the local proof: instance allocation, package mount and the cross-region database (the function app is in `belgiumcentral`, PostgreSQL in `northcentralus`) are not in the local number. Whether the CDS archive is accepted on Azure was not checked.

## Why a custom handler

- **Not a rewrite** (for example each endpoint as a Java function): there would be two codebases with the same rules, and the comparison "AKS against Functions" would compare two different programs.
- **Not the Spring Cloud Function adapter**: it needs a different entry point, a different packaging, and it does not run Spring MVC controllers, Tomcat filters or Actuator as they are. That is a second artifact again.
- **A custom handler** runs the artifact that CI already tests. One codebase, one jar, two hosts. The cost is that Azure Functions is used against its grain; Microsoft says so in the documentation (table above).

## Trade-offs in plain words

- **Cold start.** A JVM with Spring, Hibernate and Flyway needs seconds before the first answer. The first request after an idle time waits for all of it. The limit of the platform is 60 s; there is margin, but a slow database or a long migration uses it up. A long migration must not be run by this host: run it from CI or let the AKS host do it.
- **A Java runtime to patch.** The runtime is in the package, not in the platform. A JDK security update arrives only with a new build of this package. The workflow uses the current `eclipse-temurin:21-jdk-noble` image at each build, so a scheduled run each month would keep it fresh. No such schedule exists yet.
- **A frozen instance and background threads.** Between requests the instance does no work. Pollers, scheduled jobs, metric pushers and Hikari housekeeping do not run on time. That is why the relay is off, `minimum-idle` is 0 and `max-lifetime` is short: a connection that the server closed while the instance was frozen is replaced at the next request and not kept.
- **Connection pool against a small database.** The server (B1ms) accepts about 50 connections. Each instance may open 3. The scale-out limit of the function app is 4 instances (Terraform variable `function_max_instances`), so 12 connections at most, plus the AKS pods. Raise that limit only with a pooler such as PgBouncer in front.
- **Flyway on each cold start.** When the schema is current it is one fast query. Two instances that start at the same time are safe (Flyway takes a lock), but the second one waits.
- **Size.** 129 MB of zip for a few endpoints. The package mount is part of each cold start.
- **CDS archive is fragile by design.** The JVM checks the paths, sizes and times of the jars against the archive. If the platform changes the file times when it mounts the package, the JVM refuses the archive, prints a `[warning][cds]` line and starts without it (slower, not broken). Whether Flex Consumption keeps the times: `UNVERIFIED`; look for that warning in the first logs.

## What a team would do next

1. Measure the cold start on Azure over many calls (p50/p95 from Application Insights; today there are 2 cold samples) and decide if one always-ready instance is worth its price.
2. **CRaC** (checkpoint/restore, with a CRaC-enabled JDK) or a **GraalVM native image** of the API: start in tens or hundreds of milliseconds. Both change the build and need test work (reflection hints, Hibernate), so they are a project, not a flag.
3. Move the migration out of the start path (a CI step), then set `spring.flyway.enabled=false` on this host.
4. A monthly scheduled build for JDK patches, and a check of the zip in CI with a vulnerability scanner.
5. If the events must not wait for AKS: a timer-trigger function that drains the outbox.

## Example

A visitor opens the site at night, no AKS window is open, and the function app is idle. The gateway sends `GET /api/v1/contracts` with the host key. Azure allocates an instance and mounts the zip; the Functions host starts `runtime/bin/java`; Spring starts, Flyway sees a current schema, and the host forwards the request. The visitor waits about 19 s (measured live; the gateway marks the trace `cold=true` when the call takes more than 8 s). The next requests take milliseconds. After some idle minutes the instance goes away and the price returns to zero. On AKS the same jar answers at once, but the node costs money for each minute of the window. That difference is what the platform shows.
