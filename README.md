# consignado-serverless
Serverless side of the consignado platform demo: the Java gateway and the Spring Boot API on Azure Functions.

Live behind <https://consignado.kmads.dev> since 2026-11-17. Platform documentation (architecture, decisions, runbook, status): [consignado-api/docs/platform](https://github.com/kmadsdev/consignado-api/blob/main/docs/platform/README.md).

## gateway

`gateway/` is the Java 21 Azure Functions app `func-consignado-gw-km01`: the only door to the two API hosts. It checks the Entra ID token, applies the access rules (guest, user, admin), starts and stops the AKS cluster for 15-minute access windows, and returns a trace of each proxied call. Routes, configuration, tests and security decisions: [`gateway/README.md`](gateway/README.md). Deploy: `.github/workflows/gateway.yml` (push to `main`).

## api-host

`api-host/` packages the unchanged Spring Boot API of [`consignado-api`](https://github.com/kmadsdev/consignado-api) as an Azure Functions **custom handler** (Flex Consumption, runtime `custom`). The package holds the API jar, a small Java 21 runtime made with `jlink`, and one catch-all HTTP function that forwards each request to the API. Build: `api-host/build.sh <path of the API source>`. Local proof: `api-host/prove-local.sh`. Deploy: `.github/workflows/api-host.yml` (push to `main`, or by hand with an API ref). Details, measured times and trade-offs: [`api-host/README.md`](api-host/README.md).
