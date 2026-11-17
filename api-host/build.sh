#!/usr/bin/env bash
# Builds api-host/dist/api-host.zip: the Azure Functions custom handler package that runs the
# unchanged consignado-api jar on a bundled Java runtime (Linux x64).
#
# Usage:
#   ./build.sh <path-to-consignado-api-source>
#   ./build.sh --ref <git ref>          # clones kmadsdev/consignado-api at the ref (branch, tag or SHA)
#
# Needs: bash, docker, zip, git (only with --ref). Nothing else: Maven and the JDK run in containers.
# Environment:
#   API_REPO       git URL for --ref            (default https://github.com/kmadsdev/consignado-api.git)
#   JDK_IMAGE      build image                  (default eclipse-temurin:21-jdk-noble)
#   JAR_PLATFORM   platform of the Maven build  (default: the Docker default; the jar has no native code)
#   CDS            1 (default) adds an application CDS archive; 0 packages the fat jar as it is
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_REPO="${API_REPO:-https://github.com/kmadsdev/consignado-api.git}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk-noble}"
RUNTIME_PLATFORM="linux/amd64"   # the Flex Consumption instance is Linux x64
CDS="${CDS:-1}"                  # 1 = add an application class data sharing archive
CACHE="$HERE/.cache"
DIST="$HERE/dist"
PKG="$DIST/package"

# Modules that Spring Boot + Hibernate + PostgreSQL + Flyway + Jackson + Tomcat need.
# The modules that jdeps finds in the jar are added to this list.
BASE_MODULES="java.base,java.logging,java.naming,java.sql,java.management,java.instrument,java.desktop,java.security.jgss,java.security.sasl,java.net.http,java.xml,java.compiler,java.rmi,java.transaction.xa,java.prefs,java.scripting,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.naming.dns,jdk.zipfs"

log() { printf '[build] %s\n' "$*" >&2; }
die() { printf '[build] ERROR: %s\n' "$*" >&2; exit 1; }

[ $# -ge 1 ] || die "usage: build.sh <api-source-dir> | --ref <git ref>"
command -v docker >/dev/null || die "docker is not installed"
command -v zip >/dev/null || die "zip is not installed"

if [ "$1" = "--ref" ]; then
  [ $# -eq 2 ] || die "--ref needs a git ref"
  SRC="$CACHE/src"
  rm -rf "$SRC"; mkdir -p "$SRC"
  log "clone $API_REPO at $2"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin "$API_REPO"
  git -C "$SRC" fetch -q --depth 1 origin "$2"
  git -C "$SRC" checkout -q FETCH_HEAD
else
  SRC="$(cd "$1" 2>/dev/null && pwd)" || die "not a directory: $1"
fi
[ -f "$SRC/pom.xml" ] && [ -f "$SRC/mvnw" ] || die "no pom.xml and mvnw in $SRC"
API_COMMIT="$(git -C "$SRC" rev-parse HEAD 2>/dev/null || echo unknown)"

rm -rf "$DIST"
mkdir -p "$PKG" "$CACHE/m2" "$DIST/work"

# ---- 1. jar + module list (platform independent) -------------------------------------------
log "build the jar (source $SRC, commit $API_COMMIT)"
docker run --rm --name apihost-build-jar ${JAR_PLATFORM:+--platform "$JAR_PLATFORM"} \
  -v "$SRC":/src:ro -v "$CACHE/m2":/cache/m2 -v "$DIST/work":/out \
  -e MAVEN_USER_HOME=/cache/m2 -e BASE_MODULES="$BASE_MODULES" \
  "$JDK_IMAGE" bash -euo pipefail -c '
    mkdir /work && cd /src
    tar --exclude=./target --exclude=./.git -cf - . | tar -xf - -C /work
    cd /work && chmod +x mvnw
    ./mvnw -B -ntp -DskipTests -Dmaven.repo.local=/cache/m2/repository package
    cp target/consignado-api.jar /out/consignado-api.jar
    mkdir /x && cd /x && jar -xf /out/consignado-api.jar
    found="$(jdeps --ignore-missing-deps --multi-release 21 --print-module-deps --recursive \
               --class-path "BOOT-INF/lib/*" BOOT-INF/classes 2>/dev/null | grep -E "^(java|jdk)\.[a-z.,]+$" || true)"
    printf "%s,%s" "$BASE_MODULES" "$found" | tr "," "\n" | grep -E "^(java|jdk)\.[a-z.]+$" | sort -u | paste -sd, - > /out/modules.txt
    echo "[build] jdeps found: ${found:-nothing}" >&2
  '
MODULES="$(cat "$DIST/work/modules.txt")"
[ -n "$MODULES" ] || die "empty module list"
log "modules: $MODULES"

# ---- 2. Java runtime for Linux x64 + class data sharing archive ------------------------------
# The same runtime that will run in Azure makes the archive, so this step runs as linux/amd64.
# The training run stops after the Spring context refresh and needs no database.
log "build the Java runtime with jlink ($RUNTIME_PLATFORM), CDS=$CDS"
docker run --rm --name apihost-build-runtime --platform "$RUNTIME_PLATFORM" \
  -v "$DIST/work":/in:ro -v "$PKG":/pkg -e MODULES="$MODULES" -e CDS="$CDS" \
  "$JDK_IMAGE" bash -euo pipefail -c '
    mkdir /stage && cd /stage
    jlink --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files \
          --compress zip-6 --generate-cds-archive --output runtime
    runtime/bin/java -version 2>&1 | head -1 > /pkg/java-version.txt
    if [ "$CDS" = 1 ]; then
      # Spring Boot "extract": the same classes and libraries as the fat jar, as plain jars that
      # the JVM can archive (classes inside a nested jar cannot go into a CDS archive).
      runtime/bin/java -Djarmode=tools -jar /in/consignado-api.jar extract --destination app
      runtime/bin/java -XX:ArchiveClassesAtExit=app/consignado-api.jsa \
        -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.sql.init.mode=never -Dconsignado.outbox.relay.enabled=false \
        -jar app/consignado-api.jar > /tmp/training.log 2>&1 || { tail -40 /tmp/training.log; exit 1; }
      test -s app/consignado-api.jsa
    else
      mkdir app && cp -p /in/consignado-api.jar app/consignado-api.jar
    fi
    # Copy without symbolic links and with the file times: the archive check compares them.
    tar -chf - app runtime | tar -xf - -C /pkg
  '

# ---- 3. package ------------------------------------------------------------------------------
log "assemble the package"
cp "$HERE/host.json" "$PKG/host.json"
mkdir -p "$PKG/proxy" "$PKG/config"
cp "$HERE/proxy/function.json" "$PKG/proxy/function.json"
cp "$HERE/config/application.properties" "$PKG/config/application.properties"
chmod -R u+rwX,go+rX "$PKG"
chmod 755 "$PKG"/runtime/bin/* "$PKG"/runtime/lib/jspawnhelper 2>/dev/null || true
JAVA_VERSION="$(tr -d '"' < "$PKG/java-version.txt")"; rm -f "$PKG/java-version.txt"
cat > "$PKG/build-info.json" <<JSON
{
  "apiCommit": "$API_COMMIT",
  "java": "$JAVA_VERSION",
  "modules": "$MODULES",
  "cdsArchive": $([ -f "$PKG/app/consignado-api.jsa" ] && echo true || echo false),
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON

# No secret may be in the package: fail when a file that usually holds one is found.
if find "$PKG" \( -name '.env*' -o -name 'local.settings.json' -o -name '*.pem' -o -name '*.key' \) | grep -q .; then
  die "a secret-like file is in the package"
fi

( cd "$PKG" && zip -q -r "$DIST/api-host.zip" . )   # keeps modes and file times
[ -x "$PKG/runtime/bin/java" ] || die "runtime/bin/java is not executable"
rm -rf "$DIST/work"

log "done: $DIST/api-host.zip ($(du -h "$DIST/api-host.zip" | cut -f1)), runtime $(du -sh "$PKG/runtime" | cut -f1), app $(du -sh "$PKG/app" | cut -f1)"
