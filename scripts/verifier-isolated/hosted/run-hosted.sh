#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux && $(uname -m) == x86_64 ]] || { echo 'GitHub Linux x64 only; local execution refused' >&2; exit 2; }
repo=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$repo"
export REPORT_BASE="$repo/scripts/verifier-isolated/results/hosted"
mkdir -p "$REPORT_BASE"
export VERIFIER_OWNER_ID="issue177-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
export VERIFIER_PREFIX="v177-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
export VERIFIER_PW_FILE="${RUNNER_TEMP:?}/issue177-password"
python3 -c 'import secrets,sys;from pathlib import Path;p=Path(sys.argv[1]);p.write_text(secrets.token_hex(24));p.chmod(0o600)' "$VERIFIER_PW_FILE"
export APP_CPUS=0.5 APP_MEM=512m APP_XMX=320m DB_CPUS=1 DB_MEM=1024m DB_POOL=128M
export REDIS_CPUS=0.1 REDIS_MEM=64m K6_MODE=container K6_CPUS=1 K6_MEM=1536m HEADROOM_MIB=2560
export VUS=500 MAX_VUS=1500 SKIP_BUILD=true WARMUP=false DUPLICATE_RATE=0.10 DRAIN_MAX_S=180
export MYSQL_IMAGE='mysql:8.0@sha256:62fb722c78b24245ddff1796a0fcee4a49cc5b87e0aaaf20c92d1da9e0a2497b'
export REDIS_IMAGE='redis:7-alpine@sha256:ca0acbb137c1dc3339c8b147a58fd6f42775d4599327b50e7b116c23de501af2'
export JRE_IMAGE='eclipse-temurin:17-jre@sha256:0a451f1fe167afde19385156f05af505bdcd35e5680151d886d987b999d73d53'
export K6_IMAGE='grafana/k6:1.3.0@sha256:a90b459a3768c46ad1013da53af24189f735d7112273c6ac3212ca8ed0e18656'
runner=''
cleanup() {
  status=$?; trap - EXIT
  if [[ -n "$runner" ]]; then
    for i in 1 2; do docker logs --tail 400 "$VERIFIER_PREFIX-app$i" > "$REPORT_BASE/failure-app$i.log" 2>&1 || true; done
    "$runner" down || echo 'owned stop failed' >&2
    "$runner" remove || echo 'owned remove failed' >&2
  fi
  python3 scripts/verifier-isolated/hosted/collect-evidence.py "$REPORT_BASE" "$repo/build/hosted-evidence" "$VERIFIER_PW_FILE" || echo 'artifact collection failed' >&2
  rm -f "$VERIFIER_PW_FILE"
  exit "$status"
}
trap cleanup EXIT
python3 scripts/verifier-isolated/hosted/linux-capability.py "$REPORT_BASE/capability-before.json"
for image in "$MYSQL_IMAGE" "$REDIS_IMAGE" "$JRE_IMAGE" "$K6_IMAGE"; do docker pull "$image"; done
{
  git rev-parse HEAD
  uname -srm
  java -version 2>&1
  docker version --format '{{.Client.Version}} {{.Server.Version}}'
  for image in "$MYSQL_IMAGE" "$REDIS_IMAGE" "$JRE_IMAGE" "$K6_IMAGE"; do docker image inspect --format '{{.Id}} {{json .RepoDigests}}' "$image"; done
} > "$REPORT_BASE/versions.txt"
mkdir -p build/verifier-cap
python3 -c 'from pathlib import Path;import shutil;p=next(p for p in Path("build/libs").glob("*.jar") if "plain" not in p.name);shutil.copyfile(p,"build/verifier-cap/app.jar")'
for path in stock v1; do
  runner="$repo/scripts/verifier-isolated/capped/run-capped-$path.sh"
  export DB_NAME="givemeticon_coupon_admission_loadtest_177_$path"
  python3 scripts/verifier-isolated/hosted/linux-capability.py "$REPORT_BASE/capability-$path.json"
  "$runner" up
  {
    docker exec "$VERIFIER_PREFIX-mysql" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names -e "SELECT VERSION()"'
    docker exec "$VERIFIER_PREFIX-redis-mail" redis-server --version
    docker exec "$VERIFIER_PREFIX-app1" java -version 2>&1
    docker run --rm --label "xyz.buzz.verifier.owner=$VERIFIER_OWNER_ID" --cpus "$K6_CPUS" --memory "$K6_MEM" --memory-swap "$K6_MEM" "$K6_IMAGE" version
  } > "$REPORT_BASE/runtime-versions-$path.txt"
  # Identical low-load capability/warm-up: a separate fixture, not baseline evidence.
  export RATE=100 DURATION=2s RUN_LABEL="$path-smoke" REPORT_ROOT="$REPORT_BASE/$path-smoke"
  "$runner" run
  python3 scripts/verifier-isolated/hosted/check-stage.py "$REPORT_ROOT" 200
  sleep 8 # identical post-smoke stabilization; next run captures fresh counters/fixture
  export RATE=1000 DURATION=10s RUN_LABEL="$path-10k" REPORT_ROOT="$REPORT_BASE/$path-10k"
  "$runner" run
  # Record generation/integrity honestly. A target miss does not become an application bottleneck claim.
  python3 scripts/verifier-isolated/hosted/check-stage.py "$REPORT_ROOT" 10000 --diagnostic
  "$runner" down
  "$runner" remove
  runner=''
done
python3 scripts/verifier-isolated/compare-workload.py "$REPORT_BASE/v1-10k/run" 700200000 "$REPORT_BASE/stock-10k/run" 900000000 > "$REPORT_BASE/workload-comparison.json"
