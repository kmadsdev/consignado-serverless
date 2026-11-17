#!/usr/bin/env bash
# Local proof without Azure: runs dist/api-host.zip inside the official Azure Functions host image
# (linux/amd64) beside a throwaway PostgreSQL, calls the API through the Functions host and measures.
# Every container, network and file that it makes is removed at the end (names start with apihost-).
#
# Usage: ./prove-local.sh            (after ./build.sh)
# Needs: bash, docker, curl, unzip, openssl.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ZIP="$HERE/dist/api-host.zip"
HOST_IMAGE="${HOST_IMAGE:-mcr.microsoft.com/azure-functions/base:4}"
PG_IMAGE="${PG_IMAGE:-postgres:16-alpine}"
PORT="${PORT:-18080}"
BASE="http://127.0.0.1:$PORT"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/apihost-proof.XXXXXX")"
FAILS=0

cleanup() {
  docker rm -f apihost-func apihost-pg >/dev/null 2>&1 || true
  docker network rm apihost-net >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time()*1000'; }
check() { # name expected actual
  if [ "$2" = "$3" ]; then printf 'PASS  %-58s %s\n' "$1" "$3"; else printf 'FAIL  %-58s want %s, got %s\n' "$1" "$2" "$3"; FAILS=$((FAILS+1)); fi
}
code() { curl -s -o /dev/null -w '%{http_code}' -m 30 "$@"; }

[ -f "$ZIP" ] || { echo "run ./build.sh first: $ZIP not found" >&2; exit 1; }
cleanup; mkdir -p "$WORK/wwwroot" "$WORK/secrets"
unzip -q "$ZIP" -d "$WORK/wwwroot"          # the proof uses the zip, not the build folder
# Optional: HOST_JSON=<file> replaces host.json, to try a host setting without a new build.
if [ -n "${HOST_JSON:-}" ]; then cp "$HOST_JSON" "$WORK/wwwroot/host.json"; echo "host.json replaced by $HOST_JSON"; fi

# Throwaway keys for the local host only. They are made here, never printed, and removed at the end.
DB_PW="$(openssl rand -hex 16)"
FN_KEY="$(openssl rand -hex 24)"
cat > "$WORK/secrets/host.json" <<JSON
{"masterKey":{"name":"master","value":"$(openssl rand -hex 24)","encrypted":false},
 "functionKeys":[{"name":"default","value":"$FN_KEY","encrypted":false}]}
JSON

docker network create apihost-net >/dev/null
docker run -d --name apihost-pg --network apihost-net \
  -e POSTGRES_DB=consignado -e POSTGRES_USER=consignado -e POSTGRES_PASSWORD="$DB_PW" "$PG_IMAGE" >/dev/null
until docker exec apihost-pg pg_isready -q -U consignado -d consignado; do sleep 0.5; done

T0="$(now_ms)"
docker run -d --name apihost-func --platform linux/amd64 --network apihost-net --memory 2g -p "127.0.0.1:$PORT:80" \
  -v "$WORK/wwwroot":/home/site/wwwroot:ro -v "$WORK/secrets":/azure-functions-host/Secrets \
  -e AzureWebJobsScriptRoot=/home/site/wwwroot -e FUNCTIONS_WORKER_RUNTIME=custom \
  -e AzureWebJobsSecretStorageType=files -e AzureFunctionsJobHost__Logging__Console__IsEnabled=true \
  -e DB_URL=jdbc:postgresql://apihost-pg:5432/consignado -e DB_USERNAME=consignado -e DB_PASSWORD="$DB_PW" \
  "$HOST_IMAGE" >/dev/null

# Cold start: container start -> first 200 from the API through the Functions host.
COLD=""
for _ in $(seq 1 480); do
  if [ "$(code -H "x-functions-key: $FN_KEY" "$BASE/actuator/health/readiness")" = 200 ]; then COLD=$(( $(now_ms) - T0 )); break; fi
  sleep 0.25
done
[ -n "$COLD" ] || { echo "FAIL  no 200 in 120 s. Last host log lines:"; docker logs apihost-func 2>&1 | tail -30 | cut -c1-300; exit 1; }

echo "== functional checks (through the Functions host) =="
BODY="$(curl -s -m 30 -H "x-functions-key: $FN_KEY" "$BASE/actuator/health/readiness")"
check "GET /actuator/health/readiness body" '{"status":"UP"}' "$BODY"
check "GET /actuator/health/readiness without a key" 401 "$(code "$BASE/actuator/health/readiness")"
AG="$(code -X POST -H "x-functions-key: $FN_KEY" -H 'Content-Type: application/json' \
  -D "$WORK/headers.txt" "$BASE/api/v1/agreements" \
  -d "{\"code\":\"PROOF-$(openssl rand -hex 3 | tr a-f A-F)\",\"name\":\"Proof agreement\",\"maxMarginPercent\":35,\"maxTermMonths\":96,\"monthlyInterestRate\":0.0185}")"
check "POST /api/v1/agreements" 201 "$AG"
check "GET /api/v1/contracts" 200 "$(code -H "x-functions-key: $FN_KEY" "$BASE/api/v1/contracts")"
check "GET /api/v1/agreements" 200 "$(code -H "x-functions-key: $FN_KEY" "$BASE/api/v1/agreements")"
check "PATCH reaches Spring (unknown customer -> 404)" 404 \
  "$(code -X PATCH -H "x-functions-key: $FN_KEY" -H 'Content-Type: application/json' -d '{"monthlyIncome":1000}' \
     "$BASE/api/v1/customers/00000000-0000-0000-0000-000000000000/income")"
check "DELETE is not routed to the handler" 404 "$(code -X DELETE -H "x-functions-key: $FN_KEY" "$BASE/api/v1/agreements")"
ADMIN="$(code -H "x-functions-key: $FN_KEY" "$BASE/admin/host/status")"   # 401 or 403 from the host, never the Spring 404
check "/admin/host/status stays with the Functions host" "refused" "$(if [ "$ADMIN" = 401 ] || [ "$ADMIN" = 403 ]; then echo refused; else echo "$ADMIN"; fi)"
check "/runtime/webhooks/x stays with the Functions host" "not-spring" "$(curl -s -m 30 -H "x-functions-key: $FN_KEY" "$BASE/runtime/webhooks/x" | grep -q '"instance"' && echo spring || echo not-spring)"
check "GET / (root) with a key reaches Spring (404 problem)" 404 "$(code -H "x-functions-key: $FN_KEY" "$BASE/")"
POOL="$(curl -s -m 30 -H "x-functions-key: $FN_KEY" "$BASE/actuator/metrics/hikaricp.connections.max" | sed -n 's/.*"value":\([0-9.]*\).*/\1/p')"
check "Hikari maximum pool size from config/application.properties" 3.0 "$POOL"
RELAY="$(docker logs apihost-func 2>&1 | grep -c 'scheduling-' || true)"
check "outbox relay thread log lines (relay is off)" 0 "$RELAY"
if [ -f "$WORK/wwwroot/app/consignado-api.jsa" ]; then
  check "CDS archive accepted by the JVM (warnings in the log)" 0 "$(docker logs apihost-func 2>&1 | grep -c -E '\[warning\]\[cds' || true)"
fi
echo "      Location header of the 201: $(grep -i '^location:' "$WORK/headers.txt" | tr -d '\r' | cut -d' ' -f2)"

echo "== measurements (linux/amd64 under emulation when the machine is arm64: $(uname -m)) =="
TIMES="$(for _ in $(seq 1 30); do curl -s -o /dev/null -w '%{time_total}\n' -m 30 -H "x-functions-key: $FN_KEY" "$BASE/api/v1/contracts"; done | sort -n)"
WARM_P50="$(echo "$TIMES" | sed -n '15p')"; WARM_MAX="$(echo "$TIMES" | tail -1)"
TIMES_H="$(for _ in $(seq 1 30); do curl -s -o /dev/null -w '%{time_total}\n' -m 30 -H "x-functions-key: $FN_KEY" "$BASE/actuator/health/readiness"; done | sort -n)"
JAVA_START="$(docker logs apihost-func 2>&1 | sed -n 's/.*Started ConsignadoApplication in \([0-9.]*\) seconds (process running for \([0-9.]*\)).*/\1 s (JVM \2 s)/p' | head -1)"
RSS_KB="$(docker exec apihost-func sh -c 'for p in /proc/[0-9]*; do if [ "$(cat $p/comm 2>/dev/null)" = java ]; then grep VmRSS $p/status | tr -s " \t" " " | cut -d" " -f2; fi; done' | head -1)"
echo "cold start (container start -> first 200 on readiness): ${COLD} ms"
echo "Spring Boot start, from its own log:                    ${JAVA_START}"
echo "warm GET /api/v1/contracts, 30 calls:                   p50 ${WARM_P50} s, max ${WARM_MAX} s"
echo "warm GET /actuator/health/readiness, 30 calls:          p50 $(echo "$TIMES_H" | sed -n '15p') s"
echo "Java process memory (VmRSS) after the calls:            $(( RSS_KB / 1024 )) MB"
echo "container memory (host + Java):                         $(docker stats --no-stream --format '{{.MemUsage}}' apihost-func)"

if [ "$FAILS" -eq 0 ]; then echo "PROOF: PASS"; else echo "PROOF: FAIL ($FAILS)"; exit 1; fi
