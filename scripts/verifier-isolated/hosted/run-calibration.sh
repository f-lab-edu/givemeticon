#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux && $(uname -m) == x86_64 ]] || { echo 'hosted x64 only' >&2; exit 2; }
repo=$(cd "$(dirname "$0")/../../.." && pwd); cd "$repo"
export REPORT_BASE="$repo/scripts/verifier-isolated/results/calibration"
mkdir -p "$REPORT_BASE"
cleanup() {
  status=$?; trap - EXIT
  python3 scripts/verifier-isolated/hosted/verify-observer-scope.py "$REPORT_BASE/observer-scope-final.json" || echo 'final observer scope evidence failed' >&2
  cleanup_status=0
  bash scripts/verifier-isolated/hosted/cleanup-calibration.sh || cleanup_status=$?
  [[ $status != 0 || $cleanup_status == 0 ]] || status=$cleanup_status
  exit "$status"
}
trap cleanup EXIT
# Validate inherited aggregate scope BEFORE creation or polling; no fallback without caps.
python3 scripts/verifier-isolated/hosted/verify-observer-scope.py "$REPORT_BASE/observer-scope-initial.json"
python3 scripts/verifier-isolated/hosted/linux-capability.py "$REPORT_BASE/capability-B.json" --profile B
export VERIFIER_OWNER_ID="issue177-b-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
export VERIFIER_PREFIX="v177b-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
export VERIFIER_PW_FILE="${RUNNER_TEMP:?}/issue177-b-password"
python3 -c 'import secrets,sys;from pathlib import Path;p=Path(sys.argv[1]);p.write_text(secrets.token_hex(24));p.chmod(0o600)' "$VERIFIER_PW_FILE"
export APP_CPUS=0.55 APP_MEM=768m APP_XMX=512m DB_CPUS=1 DB_MEM=1024m DB_POOL=128M
export REDIS_CPUS=0.05 REDIS_MEM=64m K6_MODE=container K6_CPUS=0.75 K6_MEM=3072m HEADROOM_MIB=3328
# Entire finite2s window, plus one boundary slot. Never initialize10k VUs here.
export VUS=201 MAX_VUS=201 RATE=100 DURATION=2s DUPLICATE_RATE=0.10
export CAPTURE_K6_RUNTIME=true SKIP_BUILD=true WARMUP=false DRAIN_MAX_S=180 PROM_INTERVAL_S=1 PROM_TIMEOUT_S=3
export MYSQL_IMAGE='mysql:8.0@sha256:62fb722c78b24245ddff1796a0fcee4a49cc5b87e0aaaf20c92d1da9e0a2497b'
export REDIS_IMAGE='redis:7-alpine@sha256:ca0acbb137c1dc3339c8b147a58fd6f42775d4599327b50e7b116c23de501af2'
export JRE_IMAGE='eclipse-temurin:17-jre@sha256:0a451f1fe167afde19385156f05af505bdcd35e5680151d886d987b999d73d53'
export K6_IMAGE='grafana/k6:1.3.0@sha256:a90b459a3768c46ad1013da53af24189f735d7112273c6ac3212ca8ed0e18656'
for image in "$MYSQL_IMAGE" "$REDIS_IMAGE" "$JRE_IMAGE" "$K6_IMAGE"; do docker pull "$image"; done
python3 - <<'PY'
import json,subprocess
from pathlib import Path
Path('scripts/verifier-isolated/results/calibration/contract.json').write_text(json.dumps({'sha':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'profile':'B','service_cpu':2.7,'broker_slot_cpu':.5,'broker_slot_memory_mib':768,'broker_slot':'EMPTY','total_cpu':3.95,'total_memory_mib':9088,'paths':['stock','v1'],'rate':100,'duration_s':2,'duplicate_rate':.1,'pre_allocated_vus':201,'max_vus':201,'warmup':False,'phase':'small fixture/capability calibration, not baseline','get_smoke':'V1 recorded members after quiescence; stock has no equivalent final GET endpoint; report unsupported rather than invent endpoint'},indent=2)+'\n')
PY
mkdir -p build/verifier-cap
python3 -c 'from pathlib import Path;import shutil;shutil.copyfile(next(p for p in Path("build/libs").glob("*.jar") if "plain" not in p.name),"build/verifier-cap/app.jar")'
for path in stock v1; do
  runner="$repo/scripts/verifier-isolated/capped/run-capped-$path.sh"
  export DB_NAME="givemeticon_coupon_admission_loadtest_177_b_$path"
  export RUN_LABEL="b-$path-smoke" REPORT_ROOT="$REPORT_BASE/$path-smoke"
  "$runner" up
  python3 scripts/verifier-isolated/hosted/verify-calibration-caps.py "$VERIFIER_PREFIX" "$VERIFIER_OWNER_ID" "$REPORT_BASE/caps-$path.json"
  "$runner" run
  python3 scripts/verifier-isolated/hosted/verify-calibration-caps.py generator "$REPORT_ROOT/run/k6-cgroup.jsonl" "$VERIFIER_OWNER_ID" "$REPORT_ROOT/generator-limits.json"
  python3 scripts/verifier-isolated/hosted/check-stage.py "$REPORT_ROOT" 200 --diagnostic
  python3 scripts/verifier-isolated/hosted/final-generation-gate.py "$REPORT_ROOT" > "$REPORT_ROOT/generation-gate.json"
  if [[ $path == v1 ]]; then
    python3 scripts/verifier-isolated/hosted/verify-observer-scope.py "$REPORT_BASE/observer-before-get.json"
    python3 scripts/verifier-isolated/hosted/get-smoke.py "$REPORT_ROOT" 'http://127.0.0.1:18080,http://127.0.0.1:18081'
    python3 scripts/verifier-isolated/hosted/verify-observer-scope.py "$REPORT_BASE/observer-after-get.json"
  fi
  "$runner" down
  "$runner" remove
done
