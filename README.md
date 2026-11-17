# consignado-serverless
Serverless side of the consignado platform demo: the Java gateway and the Spring Boot API on Azure Functions.

## api-host

`api-host/` packages the unchanged Spring Boot API of [`consignado-api`](https://github.com/kmadsdev/consignado-api) as an Azure Functions **custom handler** (Flex Consumption, runtime `custom`). The package holds the API jar, a small Java 21 runtime made with `jlink`, and one catch-all HTTP function that forwards each request to the API. Build: `api-host/build.sh <path of the API source>`. Local proof: `api-host/prove-local.sh`. Details, measured times and trade-offs: [`api-host/README.md`](api-host/README.md).
