# consignado-serverless
Serverless side of the consignado platform demo: the Java gateway and the Spring Boot API on Azure Functions.

## gateway

`gateway/` is the Java 21 Azure Functions app `func-consignado-gw-km01`: the only door to the two API hosts. It checks the Entra ID token, applies the access rules (guest, user, admin), starts and stops the AKS cluster for 15-minute access windows, and returns a trace of each proxied call. Routes, configuration, tests and security decisions: [`gateway/README.md`](gateway/README.md). Deploy: `.github/workflows/gateway.yml` (push to `main`).
