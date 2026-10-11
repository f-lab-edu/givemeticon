#!/usr/bin/env bash
set -euo pipefail

# 접수(묶음 경로) + 발급 워커(CouponEventIssuanceWorker) + 상태 조회를 실제 API로 연결한 통합
# 부하테스트. 접수 단독 검증(scripts/coupon-admission/run-admission-loadtest.sh)에서 검증한
# 예열 절차·TIME_WAIT 정리를 그대로 재사용한다. 애플리케이션 처리 로직·배치 크기·커넥션 풀·Redis는
# 건드리지 않는다.

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
container=${MYSQL_CONTAINER:-givemeticon-mysql}
mysql_host_port=${MYSQL_HOST_PORT:-3306}
base_profile=${BASE_PROFILE:-local}
extra_config_location=${SPRING_CONFIG_ADDITIONAL_LOCATION:-}
database=${COUPON_ADMISSION_LOADTEST_DB:-givemeticon_coupon_admission_loadtest_integrated}
report_root=${REPORT_ROOT:-"$repo_dir/기록/coupon-integrated-loadtest"}
run_root="$report_root/runs"
port_a=${COUPON_ADMISSION_PORT_A:-18080}
port_b=${COUPON_ADMISSION_PORT_B:-18081}
rate=${RATE:-1000}
duration=${DURATION:-10s}
# 2026-09-27 진단으로 낮췄다(docs/latency/03-generator-normalization.md) - 실측 동시 VU 피크가
# 5,241이었다. 부족해지면(dropped_iterations>0) 다시 올린다.
pre_vus=${PRE_ALLOCATED_VUS:-6500}
max_vus=${MAX_VUS:-8000}
http_timeout=${HTTP_TIMEOUT:-10s}
duplicate_rate=${DUPLICATE_RATE:-0.10}
poll_base_ms=${POLL_BASE_MS:-1000}
poll_jitter_ms=${POLL_JITTER_MS:-300}
poll_budget_ms=${POLL_BUDGET_MS:-180000}
graceful_stop=${GRACEFUL_STOP:-200s}
run_count=${RUN_COUNT:-3}
run_label=${RUN_LABEL:-integrated}
diagnostics_enabled=${ADMISSION_DIAGNOSTICS_ENABLED:-false}
stock_total=${STOCK_TOTAL:-100}
stock_high=${STOCK_HIGH:-50}
warmup_enabled=${WARMUP_ENABLED:-true}
warmup_rate=${WARMUP_RATE:-1000}
warmup_duration=${WARMUP_DURATION:-10s}
issuance_batch_enabled=${ISSUANCE_BATCH_ENABLED:-false}
active_profiles="$base_profile,coupon-admission,coupon-admission-test,coupon-admission-batch,coupon-issuance"
[[ "$issuance_batch_enabled" == true ]] && active_profiles="$active_profiles,coupon-issuance-batch"
[[ -n "${EXTRA_PROFILES:-}" ]] && active_profiles="$active_profiles,$EXTRA_PROFILES"

[[ "$database" == givemeticon_coupon_admission_loadtest* ]] || { echo "refusing non-dedicated DB: $database" >&2; exit 2; }
[[ "${ALLOW_SHARED_MYSQL:-false}" == true || "$container" != givemeticon-mysql ]] || { echo "refusing shared givemeticon-mysql; set MYSQL_CONTAINER/MYSQL_HOST_PORT to an isolated instance" >&2; exit 2; }
docker ps --format '{{.Names}}' | grep -qx "$container" || { echo "MySQL container is not running: $container" >&2; exit 2; }
command -v k6 >/dev/null || { echo 'k6 is required' >&2; exit 2; }
mkdir -p "$run_root"

# JDBC requires a host environment value; never pass it in host argv or logs.
mysql_password=$(docker exec "$container" printenv MYSQL_ROOT_PASSWORD)
app_a_pid=''
app_b_pid=''
cleanup() {
  [[ -n "$app_a_pid" ]] && kill "$app_a_pid" 2>/dev/null || true
  [[ -n "$app_b_pid" ]] && kill "$app_b_pid" 2>/dev/null || true
}
trap cleanup EXIT

docker_mysql() {
  docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}
mysql_exec() {
  docker_mysql "$database" --batch --skip-column-names -e "$1"
}

docker_mysql -e "DROP DATABASE IF EXISTS \`$database\`; CREATE DATABASE \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker_mysql "$database" < "$repo_dir/src/main/resources/db/migration/V20260920__add_coupon_event_admission_ledger.sql"
docker_mysql "$database" < "$repo_dir/src/main/resources/db/migration/V20260923__add_coupon_award.sql"
docker_mysql "$database" < "$repo_dir/src/main/resources/db/migration/V20260923_2__add_coupon_award_redemption.sql"

./gradlew bootJar >/dev/null
jar_path=$(find "$repo_dir/build/libs" -maxdepth 1 -name '*.jar' ! -name '*plain*' | head -1)
[[ -n "$jar_path" ]] || { echo 'boot jar not found' >&2; exit 2; }

start_app() {
  local port=$1 log=$2
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$mysql_host_port/$database" \
  SPRING_DATASOURCE_USERNAME=root \
  SPRING_DATASOURCE_PASSWORD="$mysql_password" \
  java -jar "$jar_path" \
    --server.port="$port" \
    --spring.profiles.active="$active_profiles" \
    --coupon.admission.diagnostics.enabled="$diagnostics_enabled" \
    ${extra_config_location:+--spring.config.additional-location="$extra_config_location"} \
    --spring.flyway.enabled=false >"$log" 2>&1 &
  echo $!
}
wait_for_app() {
  local port=$1
  for _ in $(seq 1 90); do
    curl --silent --fail "http://127.0.0.1:$port/actuator/health" >/dev/null && return 0
    sleep 1
  done
  return 1
}

bootstrap_dir="$report_root/environment/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$bootstrap_dir"
app_a_pid=$(start_app "$port_a" "$bootstrap_dir/app-$port_a.log")
app_b_pid=$(start_app "$port_b" "$bootstrap_dir/app-$port_b.log")
wait_for_app "$port_a" || { echo "app A failed" >&2; exit 1; }
wait_for_app "$port_b" || { echo "app B failed" >&2; exit 1; }

{
  echo "started_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git_commit=$(git -C "$repo_dir" rev-parse HEAD)"
  echo "git_status_count=$(git -C "$repo_dir" status --short | wc -l | tr -d ' ')"
  echo "k6=$(k6 version 2>&1 | head -1)"
  java -version 2>&1 | head -2
  sysctl -n hw.ncpu 2>/dev/null | sed 's/^/host_cpu_count=/' || true
  echo "app_pids=$app_a_pid,$app_b_pid app_ports=$port_a,$port_b active_profiles=$active_profiles"
  echo "stock_total=$stock_total stock_high=$stock_high rate=$rate duration=$duration duplicate_rate=$duplicate_rate"
  echo "poll_base_ms=$poll_base_ms poll_jitter_ms=$poll_jitter_ms poll_budget_ms=$poll_budget_ms"
  grep -E 'CouponEventIssuanceWorker|CouponIssueAsyncWorker|CouponBatchIssueWorker' "$bootstrap_dir/app-$port_a.log" || true
} > "$bootstrap_dir/environment.txt"
cp "$repo_dir/src/main/resources/mapper/CouponEventMapper.xml" "$bootstrap_dir/CouponEventMapper.xml"
cp "$repo_dir/src/main/resources/mapper/CouponApplicationMapper.xml" "$bootstrap_dir/CouponApplicationMapper.xml"

create_event() {
  local name=$1 total=$2 high=$3
  mysql_exec "INSERT INTO coupon_event (public_id,status,starts_at_utc,total_quantity,high_quantity,high_points,normal_points,settings_locked_at) VALUES ('$name','SCHEDULED',UTC_TIMESTAMP(6),$total,$high,10000,5000,UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();" | tail -1
}

scrape_snapshot_with_retry() {
  local run_dir=$1 label=$2 url=$3 out_file=$4
  local attempts=${SNAPSHOT_SCRAPE_RETRIES:-5} delay=${SNAPSHOT_SCRAPE_RETRY_DELAY:-1} n=1
  while [[ $n -le $attempts ]]; do
    curl --silent --show-error --fail --max-time 2 "$url" > "$out_file" 2>>"$run_dir/monitor-errors.log" && return 0
    n=$((n + 1)); sleep "$delay"
  done
  rm -f "$out_file" 2>/dev/null || true
  printf '%s\t%s\tmissing\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$label" >> "$run_dir/metrics-missing.txt"
  return 1
}

wait_for_time_wait_drain() {
  local max_wait=${INTER_RUN_TIME_WAIT_MAX_SECONDS:-40} threshold=${INTER_RUN_TIME_WAIT_THRESHOLD:-50} waited=0
  while [[ $waited -lt $max_wait ]]; do
    local count
    count=$(netstat -an -p tcp 2>/dev/null | grep -E "\.($port_a|$port_b)[[:space:]]" | grep -c 'TIME_WAIT' || true)
    [[ -z "$count" ]] && count=0
    if [[ "$count" -le "$threshold" ]]; then
      echo "inter_run_time_wait_drain: ${waited}s elapsed, ${count} TIME_WAIT left" >&2
      return 0
    fi
    sleep 2; waited=$((waited + 2))
  done
  echo "inter_run_time_wait_drain: gave up after ${max_wait}s" >&2
  return 1
}

# 발급 워커가 더 이상 처리할 게 없고(모든 PENDING/CHECKING이 ISSUED/SOLD_OUT로 확정), 두 앱의
# Hikari 대여/획득 대기가 모두 0인 상태를 "quiescent"로 본다.
wait_for_issuance_quiescence() {
  local event_id=$1 run_dir=$2 max_wait=${3:-300}
  local deadline=$((SECONDS + max_wait)) stable=0 previous=""
  while [[ $SECONDS -lt $deadline ]]; do
    local row
    row=$(mysql_exec "SELECT SUM(status IN ('PENDING','CHECKING')), SUM(status='ISSUED'), SUM(status='SOLD_OUT'), COUNT(*) FROM coupon_application WHERE event_id=$event_id")
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ)	$row" >> "$run_dir/issuance-poll.tsv"
    local unresolved=${row%%$'\t'*}
    active_a=$(curl --silent --max-time 2 "http://127.0.0.1:$port_a/actuator/prometheus" 2>/dev/null | awk '$1 ~ /^hikaricp_connections_active(\{| )/ {s+=$NF} END{print s+0}')
    active_b=$(curl --silent --max-time 2 "http://127.0.0.1:$port_b/actuator/prometheus" 2>/dev/null | awk '$1 ~ /^hikaricp_connections_active(\{| )/ {s+=$NF} END{print s+0}')
    if [[ "${unresolved:-0}" == "0" && "${active_a:-0}" == 0 && "${active_b:-0}" == 0 && "$row" == "$previous" ]]; then
      stable=$((stable + 1))
      [[ $stable -ge 2 ]] && { echo "quiescent" > "$run_dir/issuance-completion-status.txt"; return 0; }
    else
      stable=0
    fi
    previous=$row
    sleep 2
  done
  echo "not-confirmed-within-${max_wait}s" > "$run_dir/issuance-completion-status.txt"
  return 1
}

run_one() {
  local run_id=$1 kind=$2 event_id=$3 member_start=$4 run_rate=$5 run_duration=$6 dup_rate=$7 poll_budget=$8
  local run_dir="$run_root/$run_id"
  mkdir -p "$run_dir"
  printf 'run_id=%s\nkind=%s\nevent_id=%s\nmember_id_start=%s\nrate=%s\nduration=%s\nduplicate_rate=%s\npoll_budget_ms=%s\nstarted_at_utc=%s\n' \
    "$run_id" "$kind" "$event_id" "$member_start" "$run_rate" "$run_duration" "$dup_rate" "$poll_budget" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$run_dir/run.env"
  scrape_snapshot_with_retry "$run_dir" app1 "http://127.0.0.1:$port_a/actuator/prometheus" "$run_dir/app1-before.prom" || true
  scrape_snapshot_with_retry "$run_dir" app2 "http://127.0.0.1:$port_b/actuator/prometheus" "$run_dir/app2-before.prom" || true

  local script_path
  if [[ "$kind" == warmup ]]; then
    script_path="$repo_dir/scripts/coupon-admission/admission-arrival-rate.js"
  else
    script_path="$repo_dir/scripts/coupon-integrated-loadtest/integrated-admission-poll.js"
  fi

  set +e
  EVENT_ID="$event_id" MEMBER_ID_START="$member_start" RATE="$run_rate" DURATION="$run_duration" \
    PRE_ALLOCATED_VUS="$pre_vus" MAX_VUS="$max_vus" HTTP_TIMEOUT="$http_timeout" \
    DUPLICATE_RATE="$dup_rate" POLL_BASE_MS="$poll_base_ms" POLL_JITTER_MS="$poll_jitter_ms" \
    POLL_BUDGET_MS="$poll_budget" GRACEFUL_STOP="$graceful_stop" \
    TARGETS="http://127.0.0.1:$port_a,http://127.0.0.1:$port_b" \
    k6 run --out json="$run_dir/k6.json" --summary-export "$run_dir/k6-summary.json" \
      --console-output "$run_dir/k6-failures.log" "$script_path" \
      > "$run_dir/k6-console.log" 2>&1 &
  k6_pid=$!
  bash "$repo_dir/scripts/coupon-admission/monitor-admission.sh" "$run_dir" "$event_id" "$app_a_pid" "$app_b_pid" "$k6_pid" &
  monitor_pid=$!
  fast_sampler_pid=''
  if [[ "$kind" == measurement && "${FAST_SAMPLER:-false}" == true ]]; then
    python3 "$repo_dir/scripts/verifier-isolated/fast-sampler.py" "$run_dir" "$container" "$database" "$event_id" "$port_a" "$port_b" "${FAST_SAMPLER_INTERVAL:-0.2}" &
    fast_sampler_pid=$!
  fi
  bash "$repo_dir/scripts/coupon-integrated-loadtest/monitor-generator.sh" "$run_dir" "$k6_pid" 0.5 &
  generator_monitor_pid=$!
  wait "$k6_pid"
  k6_status=$?
  set -e
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  kill "$generator_monitor_pid" 2>/dev/null || true
  wait "$generator_monitor_pid" 2>/dev/null || true
  echo "k6_exit_code=$k6_status" >> "$run_dir/run.env"

  scrape_snapshot_with_retry "$run_dir" app1 "http://127.0.0.1:$port_a/actuator/prometheus" "$run_dir/app1-after.prom" || true
  scrape_snapshot_with_retry "$run_dir" app2 "http://127.0.0.1:$port_b/actuator/prometheus" "$run_dir/app2-after.prom" || true

  if [[ "$kind" == measurement ]]; then
    wait_for_issuance_quiescence "$event_id" "$run_dir" 300 || true
    if [[ -n "$fast_sampler_pid" ]]; then kill "$fast_sampler_pid" 2>/dev/null || true; wait "$fast_sampler_pid" 2>/dev/null || true; fi
    scrape_snapshot_with_retry "$run_dir" app1 "http://127.0.0.1:$port_a/actuator/prometheus" "$run_dir/app1-quiescent.prom" || true
    scrape_snapshot_with_retry "$run_dir" app2 "http://127.0.0.1:$port_b/actuator/prometheus" "$run_dir/app2-quiescent.prom" || true

    # 정리(cleanup)로 앱을 죽이기 전에 최종 DB 상태를 확보한다.
    mysql_exec "SELECT 'event', id, status, next_acceptance_sequence, issued_quantity, total_quantity, high_quantity FROM coupon_event WHERE id=$event_id" > "$run_dir/event-final.tsv" || true
    mysql_exec "SELECT status, COUNT(*) FROM coupon_application WHERE event_id=$event_id GROUP BY status" > "$run_dir/application-status-counts.tsv" || true
    mysql_exec "SELECT tier, COUNT(*), SUM(points) FROM coupon_award WHERE event_id=$event_id GROUP BY tier" > "$run_dir/award-tier-counts.tsv" || true
    mysql_exec "SELECT COUNT(*) FROM (SELECT application_id FROM coupon_award GROUP BY application_id HAVING COUNT(*)>1) d" > "$run_dir/duplicate-award-by-application.txt" || true
    mysql_exec "SELECT COUNT(*) FROM (SELECT member_id FROM coupon_award WHERE event_id=$event_id GROUP BY member_id HAVING COUNT(*)>1) d" > "$run_dir/duplicate-award-by-member.txt" || true
    mysql_exec "SELECT COUNT(*) FROM coupon_award WHERE event_id=$event_id" > "$run_dir/award-total.txt" || true
    mysql_exec "SELECT ca.acceptance_sequence, cw.tier FROM coupon_application ca JOIN coupon_award cw ON cw.application_id=ca.id WHERE ca.event_id=$event_id AND ((ca.acceptance_sequence<=(SELECT high_quantity FROM coupon_event WHERE id=$event_id) AND cw.tier<>'HIGH') OR (ca.acceptance_sequence>(SELECT high_quantity FROM coupon_event WHERE id=$event_id) AND cw.tier<>'NORMAL'))" > "$run_dir/tier-mismatch.tsv" || true
    mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_id AND status IN ('PENDING','CHECKING')" > "$run_dir/unresolved-after-quiescence.txt" || true
    mysql_exec "SELECT member_id, acceptance_sequence, status, public_request_id FROM coupon_application WHERE event_id=$event_id ORDER BY acceptance_sequence" > "$run_dir/applications.tsv" || true
    python3 "$repo_dir/scripts/coupon-integrated-loadtest/reconcile-integrated.py" "$run_dir" || true
  fi
  date -u +%Y-%m-%dT%H:%M:%SZ > "$run_dir/finished-at-utc.txt"
}

if [[ "$warmup_enabled" == true ]]; then
  warmup_event=$(create_event "warmup-$(date -u +%s)" 100000 50000)
  run_one "warmup-$(date -u +%Y%m%dT%H%M%SZ)" warmup "$warmup_event" 600000000 "$warmup_rate" "$warmup_duration" 0 0
fi

for attempt in $(seq 1 "$run_count"); do
  wait_for_time_wait_drain || true
  run_id="$run_label-stock${stock_total}-${stock_high}-$(date -u +%Y%m%dT%H%M%SZ)-$attempt"
  event_id=$(create_event "$run_id" "$stock_total" "$stock_high")
  run_one "$run_id" measurement "$event_id" "$((700000000 + attempt * 200000))" "$rate" "$duration" "$duplicate_rate" "$poll_budget_ms"
done

echo "runs=$run_root"
