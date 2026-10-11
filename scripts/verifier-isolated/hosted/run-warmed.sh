#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux && $(uname -m) == x86_64 ]] || { echo 'hosted x64 only' >&2; exit 2; }
repo=$(cd "$(dirname "$0")/../../.." && pwd); cd "$repo"
export REPORT_BASE="$repo/scripts/verifier-isolated/results/warmed"
mkdir -p "$REPORT_BASE"
cleanup() {
  status=$?; trap - EXIT
  python3 scripts/verifier-isolated/hosted/verify-observer-scope.py "$REPORT_BASE/observer-scope-final.json" || echo 'final observer scope evidence failed' >&2
  cleanup_status=0
  bash scripts/verifier-isolated/hosted/cleanup-warmed.sh || cleanup_status=$?
  [[ $status != 0 || $cleanup_status == 0 ]] || status=$cleanup_status
  exit "$status"
}
trap cleanup EXIT
# Validate inherited aggregate scope BEFORE creation or polling; no fallback without caps.
python3 scripts/verifier-isolated/hosted/verify-observer-scope.py "$REPORT_BASE/observer-scope-initial.json"
python3 scripts/verifier-isolated/hosted/linux-capability.py "$REPORT_BASE/capability-B.json" --profile B
export VERIFIER_OWNER_ID="issue177-w-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
export VERIFIER_PREFIX="v177w-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
export VERIFIER_PW_FILE="${RUNNER_TEMP:?}/issue177-w-password"
python3 -c 'import secrets,sys;from pathlib import Path;p=Path(sys.argv[1]);p.write_text(secrets.token_hex(24));p.chmod(0o600)' "$VERIFIER_PW_FILE"
export APP_CPUS=0.55 APP_MEM=768m APP_XMX=512m DB_CPUS=1 DB_MEM=1024m DB_POOL=128M
export REDIS_CPUS=0.05 REDIS_MEM=64m K6_MODE=container K6_CPUS=0.75 K6_MEM=3072m HEADROOM_MIB=3328
# Initial candidate only: identical warmup then separate fixture 10rps, no20/40 stages.
export VUS=201 MAX_VUS=201 RATE=10 DURATION=10s DUPLICATE_RATE=0.10 ITERATION_LEDGER=true
export CAPTURE_K6_RUNTIME=true SKIP_BUILD=true WARMUP=false DRAIN_MAX_S=180 PROM_INTERVAL_S=1 PROM_TIMEOUT_S=3
export MYSQL_IMAGE='mysql:8.0@sha256:62fb722c78b24245ddff1796a0fcee4a49cc5b87e0aaaf20c92d1da9e0a2497b'
export REDIS_IMAGE='redis:7-alpine@sha256:ca0acbb137c1dc3339c8b147a58fd6f42775d4599327b50e7b116c23de501af2'
export JRE_IMAGE='eclipse-temurin:17-jre@sha256:0a451f1fe167afde19385156f05af505bdcd35e5680151d886d987b999d73d53'
export K6_IMAGE='grafana/k6:1.3.0@sha256:a90b459a3768c46ad1013da53af24189f735d7112273c6ac3212ca8ed0e18656'
for image in "$MYSQL_IMAGE" "$REDIS_IMAGE" "$JRE_IMAGE" "$K6_IMAGE"; do docker pull "$image"; done
python3 - <<'PY'
import json,subprocess
from pathlib import Path
Path('scripts/verifier-isolated/results/warmed/contract.json').write_text(json.dumps({'sha':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'profile':'B','legacy_worker_policy':{'mode':'off','recovery_enabled':False,'v1_event_worker_enabled':True,'changed_from_cold_stock':True},'expected_prometheus_targets':{'app1':'http://127.0.0.1:18080/actuator/prometheus','app2':'http://127.0.0.1:18081/actuator/prometheus'},'service_cpu':2.7,'broker_slot_cpu':.5,'broker_slot_memory_mib':768,'broker_slot':'EMPTY','total_cpu':3.95,'total_memory_mib':9088,'paths':['stock','v1'],'rate':10,'duration_s':10,'duplicate_rate':.1,'pre_allocated_vus':201,'max_vus':201,'warmup':{'rate':10,'duration_s':20,'fixture':'separate','same_jvm':True},'phase':'preflight and warmed low-rate diagnosis; not mandatory baseline','inactive_rates':[20,40],'get_smoke':'V1 recorded members after quiescence; stock has no equivalent final GET endpoint; report unsupported rather than invent endpoint'},indent=2)+'\n')
PY
mkdir -p build/verifier-cap
python3 -c 'from pathlib import Path;import shutil;shutil.copyfile(next(p for p in Path("build/libs").glob("*.jar") if "plain" not in p.name),"build/verifier-cap/app.jar")'
export WARMED_LEGACY_WORKERS_OFF=true
export WARMED_CONTRACT_FILE="$REPORT_BASE/contract.json"
for path in stock v1; do
  runner="$repo/scripts/verifier-isolated/capped/run-capped-$path.sh"
  export DB_NAME="givemeticon_coupon_admission_loadtest_177_w_$path"
  export WARMED_POLICY_DIR="$REPORT_BASE/$path-policy"
  mkdir -p "$WARMED_POLICY_DIR"
  "$runner" up
  for app in app1 app2; do
    python3 scripts/verifier-isolated/hosted/worker-meter-policy.py "$path" "$app" "$WARMED_CONTRACT_FILE" "$WARMED_POLICY_DIR/$app.json"
  done
  python3 scripts/verifier-isolated/hosted/verify-calibration-caps.py "$VERIFIER_PREFIX" "$VERIFIER_OWNER_ID" "$REPORT_BASE/caps-$path.json"
  # The real script and imports initialize201/401 VUs; no HTTP or dummy endpoint.
  for size in 201 401; do
    python3 scripts/verifier-isolated/hosted/warmed-preflight.py "$path" "$size" "$REPORT_BASE/$path-preflight-$size"
  done
  export CALIBRATION_GET_DURING=false INITIALIZATION_PREFLIGHT=false
  export STOCK_TOTAL=1000 STOCK_HIGH=500 VUS=201 MAX_VUS=201 RATE=10
  export USER_ID_START=910000000 MEMBER_ID_START=710000000
  export RUN_LABEL="w-$path-warmup" REPORT_ROOT="$REPORT_BASE/$path-warmup" DURATION=20s
  "$runner" run
  python3 scripts/verifier-isolated/hosted/verify-calibration-caps.py "$VERIFIER_PREFIX" "$VERIFIER_OWNER_ID" "$REPORT_ROOT/service-limits-after.json"
  python3 scripts/verifier-isolated/hosted/warmed-stage.py "$REPORT_ROOT" "$path" 200 --warmup
  # Five full same-target/process, idle-pool and fixture-drain checks; no extension.
  python3 scripts/verifier-isolated/hosted/warmed-stabilization.py "$REPORT_ROOT" "$path" "$REPORT_BASE/$path-stabilization"
  export USER_ID_START=920000000 MEMBER_ID_START=720000000
  export RUN_LABEL="w-$path-10rps" REPORT_ROOT="$REPORT_BASE/$path-10rps" DURATION=10s
  "$runner" run
  python3 scripts/verifier-isolated/hosted/verify-calibration-caps.py "$VERIFIER_PREFIX" "$VERIFIER_OWNER_ID" "$REPORT_ROOT/service-limits-after.json"
  python3 scripts/verifier-isolated/hosted/warmed-stage.py "$REPORT_ROOT" "$path" 100
  "$runner" down
  "$runner" remove
done
