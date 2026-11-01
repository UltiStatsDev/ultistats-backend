#!/usr/bin/env bash
set -euo pipefail
# This runner owns its heavy lock. Call it without an outer heavy lock.
if [[ ${1:-} != --heavy-locked ]]; then
    exec flock -w 120 -x /tmp/ultistats-web-heavy-check.lock bash "$0" --heavy-locked
fi
node_bin=${NODE_BIN:-node}
root=$(mktemp -d /tmp/ultistats-bootstrap169.XXXXXXXX)
chmod 700 "$root"
namespace=$("$node_bin" -e 'process.stdout.write(require("node:crypto").randomBytes(6).toString("hex"))')
baseline=24704e11143a0bf919559a41324603f936ae03cf
pg_image=$(docker image inspect postgres:16-alpine --format '{{.Id}}' 2>/dev/null || true)
if [[ -z $pg_image ]]; then
    flock -w 120 -x /tmp/ultistats-browser-network.lock docker pull postgres:16-alpine >/dev/null
    pg_image=$(docker image inspect postgres:16-alpine --format '{{.Id}}')
fi
api_image=${BOOTSTRAP_APPLICATION_IMAGE:-}
jar_sha=${BOOTSTRAP_APPLICATION_JAR_SHA256:-}
if [[ -z $api_image ]]; then
    test -n "${JAVA_HOME:-}"
    mkdir "$root/source" "$root/context"
    git archive "$baseline" | tar -x -C "$root/source"
    export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$root/gradle-cache"}
    (cd "$root/source" && flock -w 180 -x /tmp/ultistats-backend-gradle.lock ./gradlew bootJar --no-daemon --max-workers=2 > "$root/build.log" 2>&1)
    jars=("$root/source/build/libs/"*.jar)
    test "${#jars[@]}" -eq 1
    cp "${jars[0]}" "$root/context/app.jar"
    jar_sha=$("$node_bin" -e 'const fs=require("node:fs"),c=require("node:crypto");process.stdout.write(c.createHash("sha256").update(fs.readFileSync(process.argv[1])).digest("hex"))' "$root/context/app.jar")
    "$node_bin" --input-type=module - "$baseline" "$root/context/Dockerfile" <<'NODE'
import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
const text=execFileSync('git',['show',`${process.argv[2]}:Dockerfile`],{encoding:'utf8'});
const stage=text.slice(text.indexOf('FROM eclipse-temurin:21-jre-alpine'));
if(!stage.startsWith('FROM ')||!stage.includes('COPY --from=builder'))throw new Error('Baseline Dockerfile mismatch');
writeFileSync(process.argv[3],stage.replace(/COPY --from=builder[^\n]+/,'COPY app.jar app.jar'));
NODE
    # Record planned image ownership before the only image build mutation.
    printf '%s\n' "source=$baseline image=ultistats-bootstrap169-$namespace:247" > "$root/image-build-plan.txt"
    flock -w 120 -x /tmp/ultistats-browser-network.lock docker build --label "org.opencontainers.image.revision=$baseline" -t "ultistats-bootstrap169-$namespace:247" "$root/context" > "$root/image-build.log" 2>&1
    api_image=$(docker image inspect "ultistats-bootstrap169-$namespace:247" --format '{{.Id}}')
fi
"$node_bin" --input-type=module - "$root" "$namespace" "$pg_image" "$api_image" "$jar_sha" <<'NODE'
import {writeFileSync} from 'node:fs';
const [root,ns,postgresImageId,applicationImageId,applicationJarSha256]=process.argv.slice(2);
if(!/^sha256:[a-f0-9]{64}$/.test(applicationImageId)||!/^sha256:[a-f0-9]{64}$/.test(postgresImageId)||!/^[a-f0-9]{64}$/.test(applicationJarSha256))throw new Error('Pinned immutable image/JAR inputs required');
const plan={schemaVersion:1,installationId:'ultistats_private',runtimeInstanceId:ns,root,postgresContainer:`ultistats-bootstrap169-${ns}-pg`,applicationContainer:`ultistats-bootstrap169-${ns}-api`,proxyContainer:`ultistats-bootstrap169-${ns}-sql-client`,proxyFixture:'SQL authentication source only; not PgBouncer/fencing proof',databaseVolume:`ultistats-bootstrap169-${ns}-pgdata`,databaseNetwork:`ultistats-bootstrap169-${ns}-internal`,postgresImageId,applicationImageId,applicationJarSha256,applicationSource:'24704e11143a0bf919559a41324603f936ae03cf',ports:[],networkInternal:true,consumer:'bootstrap169 source tests and actual247 split-owner/service verification',cleanup:'Exact owned containers/network after sole consumer END; retain named volume/private evidence. No global cleanup or existing runtime reuse.'};
writeFileSync(`${root}/resource-plan.json`,JSON.stringify(plan,null,2)+'\n',{mode:0o600});
NODE
export STAND_SQL_BOOTSTRAP_INTEGRATION=1 STAND_BOOTSTRAP_PLAN="$root/resource-plan.json"
printf 'Owned bootstrap evidence: %s\n' "$root"
"$node_bin" --test deploy/stand/tests/sql-bootstrap.test.mjs
