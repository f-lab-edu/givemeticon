#!/usr/bin/env bash
set -euo pipefail

# 접수 API만의 1,000 req/s x 10초 반복 검증. 전용 DB와 테스트 인증 프로필만 사용한다.
repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_ADMISSION_LOADTEST_DB:-givemeticon_coupon_admission_loadtest}
report_root=${REPORT_ROOT:-"$repo_dir/기록/coupon-admission-loadtest"}
run_root="$report_root/runs"
port_a=${COUPON_ADMISSION_PORT_A:-18080}
port_b=${COUPON_ADMISSION_PORT_B:-18081}
rate=${RATE:-1000}
duration=${DURATION:-10s}
# 첫 시도에서 6,000 VU 상한에 가까워지며 dropped_iterations가 발생했다. HTTP timeout(10초)
# 동안 1,000 req/s를 계속 시작하려면 최대 10,000 in-flight를 수용해야 하므로 상한을 보완한다.
pre_vus=${PRE_ALLOCATED_VUS:-12000}
max_vus=${MAX_VUS:-12000}
http_timeout=${HTTP_TIMEOUT:-10s}
run_count=${RUN_COUNT:-3}
run_label=${RUN_LABEL:-admission-1000rps-10s}
diagnostics_enabled=${ADMISSION_DIAGNOSTICS_ENABLED:-false}
# single(기존 단건 접수) | batch(coupon-admission-batch 프로필로 묶음 접수 경로를 켠다). 풀 크기·
# 타임아웃·DB 내구성 설정·워커 비활성화는 coupon-admission 프로필에서 그대로 물려받아 두 모드가 같다.
admission_mode=${ADMISSION_MODE:-single}
[[ "$admission_mode" == single || "$admission_mode" == batch ]] || { echo "ADMISSION_MODE must be single or batch: $admission_mode" >&2; exit 2; }
active_profiles="local,coupon-admission,coupon-admission-test"
[[ "$admission_mode" == batch ]] && active_profiles="$active_profiles,coupon-admission-batch"
mkdir -p "$run_root"

[[ "$database" == givemeticon_coupon_admission_loadtest* ]] || { echo "refusing non-dedicated DB: $database" >&2; exit 2; }
docker ps --format '{{.Names}}' | grep -qx "$container" || { echo "MySQL container is not running: $container" >&2; exit 2; }
command -v k6 >/dev/null || { echo 'k6 is required' >&2; exit 2; }

mysql_password=$(docker exec "$container" printenv MYSQL_ROOT_PASSWORD)
app_a_pid=''
app_b_pid=''
cleanup() {
  [[ -n "$app_a_pid" ]] && kill "$app_a_pid" 2>/dev/null || true
  [[ -n "$app_b_pid" ]] && kill "$app_b_pid" 2>/dev/null || true
}
trap cleanup EXIT

mysql_exec() {
  docker exec -i "$container" mysql -uroot -p"$mysql_password" "$database" --batch --skip-column-names -e "$1"
}

docker exec "$container" mysql -uroot -p"$mysql_password" -e "DROP DATABASE IF EXISTS \`$database\`; CREATE DATABASE \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i "$container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260920__add_coupon_event_admission_ledger.sql"
# 발급 워커가 꺼져 있음을 DB에서도 검증하기 위한 빈 쿠폰 테이블. 접수 API는 이 테이블을 읽거나 쓰지 않는다.
mysql_exec 'CREATE TABLE coupon (id BIGINT PRIMARY KEY AUTO_INCREMENT, application_id BIGINT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))'

./gradlew bootJar >/dev/null
jar_path=$(find "$repo_dir/build/libs" -maxdepth 1 -name '*.jar' ! -name '*plain*' | head -1)
[[ -n "$jar_path" ]] || { echo 'boot jar not found' >&2; exit 2; }

start_app() {
  local port=$1 log=$2
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/$database" \
  SPRING_DATASOURCE_USERNAME=root \
  SPRING_DATASOURCE_PASSWORD="$mysql_password" \
  java -jar "$jar_path" \
    --server.port="$port" \
    --spring.profiles.active="$active_profiles" \
    --coupon.admission.diagnostics.enabled="$diagnostics_enabled" \
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

# 실행별로 고유한 디렉터리를 쓴다 - 이전에는 고정 경로(.../environment/app-<port>.log)를 재사용해
# 같은 REPORT_ROOT로 반복 실행하면 이전 실행의 앱 로그가 다음 실행 시작과 동시에 덮어써졌다(예:
# 6단계 batch 1회차의 앱 로그가 2회차 시작 시 사라진 사례). 타임스탬프+PID로 실행마다 보존한다.
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
  sysctl -n hw.memsize 2>/dev/null | sed 's/^/host_memory_bytes=/' || true
  echo "app_pids=$app_a_pid,$app_b_pid"
  echo "app_ports=$port_a,$port_b"
  echo "load_generator_and_apps_and_mysql_share_host=true"
  echo "auth=test-only header X-Coupon-Admission-Test-Member; excludes production session/Redis authentication cost"
  echo "rate=$rate duration=$duration pre_allocated_vus=$pre_vus max_vus=$max_vus http_timeout=$http_timeout diagnostics_enabled=$diagnostics_enabled"
  echo "admission_mode=$admission_mode active_profiles=$active_profiles"
  docker exec "$container" sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "SELECT VERSION(); SELECT @@transaction_isolation; SELECT @@max_connections; SHOW VARIABLES WHERE Variable_name IN ('\''innodb_flush_log_at_trx_commit'\'', '\''sync_binlog'\'', '\''innodb_lock_wait_timeout'\'', '\''transaction_write_set_extraction'\'');"'
  echo '--- app1 prometheus at startup ---'
  curl --silent "http://127.0.0.1:$port_a/actuator/prometheus" | grep -E 'hikaricp_connections_(max|active|idle|pending)' || true
  echo '--- worker profile evidence ---'
  grep -E 'CouponIssueAsyncWorker|CouponBatchIssueWorker|CouponIssueRequestRecoveryScheduler' "$bootstrap_dir/app-$port_a.log" || true
} > "$bootstrap_dir/environment.txt"
cp "$repo_dir/src/main/resources/mapper/CouponEventMapper.xml" "$bootstrap_dir/CouponEventMapper.xml"
cp "$repo_dir/src/main/resources/mapper/CouponApplicationMapper.xml" "$bootstrap_dir/CouponApplicationMapper.xml"
cp "$repo_dir/src/main/resources/application-coupon-admission.yml" "$bootstrap_dir/application-coupon-admission.yml"
[[ "$admission_mode" == batch ]] && cp "$repo_dir/src/main/resources/application-coupon-admission-batch.yml" "$bootstrap_dir/application-coupon-admission-batch.yml"

create_event() {
  local name=$1
  mysql_exec "INSERT INTO coupon_event (public_id,status,starts_at_utc,total_quantity,high_quantity,high_points,normal_points,settings_locked_at) VALUES ('$name','SCHEDULED',UTC_TIMESTAMP(6),20000,10000,10000,5000,UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();" | tail -1
}

wait_for_quiescence() {
  local event_id=$1 run_dir=$2
  local deadline=$((SECONDS + 90))
  local stable=0
  local previous_count=""
  while [[ $SECONDS -lt $deadline ]]; do
    mysql_exec "SELECT COUNT(*), SUM(status='PENDING'), (SELECT next_acceptance_sequence FROM coupon_event WHERE id=$event_id) FROM coupon_application WHERE event_id=$event_id" >> "$run_dir/completion-poll.tsv"
    active_a=$(curl --silent "http://127.0.0.1:$port_a/actuator/prometheus" | awk '$1 ~ /^hikaricp_connections_active(\\{| )/ {sum += $NF} END {print sum+0}')
    active_b=$(curl --silent "http://127.0.0.1:$port_b/actuator/prometheus" | awk '$1 ~ /^hikaricp_connections_active(\\{| )/ {sum += $NF} END {print sum+0}')
    pending_a=$(curl --silent "http://127.0.0.1:$port_a/actuator/prometheus" | awk '$1 ~ /^hikaricp_connections_pending(\\{| )/ {sum += $NF} END {print sum+0}')
    pending_b=$(curl --silent "http://127.0.0.1:$port_b/actuator/prometheus" | awk '$1 ~ /^hikaricp_connections_pending(\\{| )/ {sum += $NF} END {print sum+0}')
    last=$(tail -1 "$run_dir/completion-poll.tsv")
    current_count=${last%%$'\t'*}
    # k6 종료 후 두 앱의 대여/획득 대기 연결이 모두 0이고, DB 신청 건수가 연속 두 표본에서
    # 같을 때만 종료한다. 신청 상태 PENDING 자체는 워커를 끈 이 실험의 정상 결과라 조건에 넣지 않는다.
    if [[ "$active_a" == 0 && "$active_b" == 0 && "$pending_a" == 0 && "$pending_b" == 0 && "$current_count" == "$previous_count" ]]; then
      stable=$((stable + 1))
      [[ $stable -ge 2 ]] && { echo "quiescent" > "$run_dir/completion-status.txt"; return 0; }
    else
      stable=0
    fi
    previous_count=$current_count
    sleep 1
  done
  echo "not-confirmed-within-90s" > "$run_dir/completion-status.txt"
  return 1
}

run_one() {
  local run_id=$1 kind=$2 event_id=$3 member_start=$4 run_rate=$5 run_duration=$6
  local run_dir="$run_root/$run_id"
  mkdir -p "$run_dir"
  printf 'run_id=%s\nkind=%s\nevent_id=%s\nmember_id_start=%s\nrate=%s\nduration=%s\nstarted_at_utc=%s\n' \
    "$run_id" "$kind" "$event_id" "$member_start" "$run_rate" "$run_duration" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$run_dir/run.env"
  curl --silent "http://127.0.0.1:$port_a/actuator/prometheus" > "$run_dir/app1-before.prom"
  curl --silent "http://127.0.0.1:$port_b/actuator/prometheus" > "$run_dir/app2-before.prom"
  set +e
  EVENT_ID="$event_id" MEMBER_ID_START="$member_start" RATE="$run_rate" DURATION="$run_duration" \
    PRE_ALLOCATED_VUS="$pre_vus" MAX_VUS="$max_vus" HTTP_TIMEOUT="$http_timeout" \
    TARGETS="http://127.0.0.1:$port_a,http://127.0.0.1:$port_b" \
    k6 run --out "json=$run_dir/k6.json" --summary-export "$run_dir/k6-summary.json" \
      --console-output "$run_dir/k6-failures.log" "$repo_dir/scripts/coupon-admission/admission-arrival-rate.js" \
      > "$run_dir/k6-console.log" 2>&1 &
  k6_pid=$!
  bash "$repo_dir/scripts/coupon-admission/monitor-admission.sh" "$run_dir" "$event_id" "$app_a_pid" "$app_b_pid" "$k6_pid" &
  monitor_pid=$!
  wait "$k6_pid"
  k6_status=$?
  set -e
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  curl --silent "http://127.0.0.1:$port_a/actuator/prometheus" > "$run_dir/app1-after.prom"
  curl --silent "http://127.0.0.1:$port_b/actuator/prometheus" > "$run_dir/app2-after.prom"
  echo "k6_exit_code=$k6_status" >> "$run_dir/run.env"
  wait_for_quiescence "$event_id" "$run_dir" || true
  curl --silent "http://127.0.0.1:$port_a/actuator/prometheus" > "$run_dir/app1-quiescent.prom"
  curl --silent "http://127.0.0.1:$port_b/actuator/prometheus" > "$run_dir/app2-quiescent.prom"
  mysql_exec "SELECT 'event', id, status, next_acceptance_sequence, issued_quantity FROM coupon_event WHERE id=$event_id; SELECT 'applications', COUNT(*), COUNT(DISTINCT member_id), COUNT(DISTINCT acceptance_sequence), MIN(acceptance_sequence), MAX(acceptance_sequence), SUM(status='PENDING'), SUM(status<>'PENDING') FROM coupon_application WHERE event_id=$event_id; SELECT 'duplicates_member', COUNT(*) FROM (SELECT member_id FROM coupon_application WHERE event_id=$event_id GROUP BY member_id HAVING COUNT(*) > 1) d; SELECT 'duplicates_sequence', COUNT(*) FROM (SELECT acceptance_sequence FROM coupon_application WHERE event_id=$event_id GROUP BY acceptance_sequence HAVING COUNT(*) > 1) d; SELECT 'coupon_rows', COUNT(*) FROM coupon; SELECT 'missing_sequence', COUNT(*) FROM (SELECT acceptance_sequence FROM coupon_application WHERE event_id=$event_id) a RIGHT JOIN (SELECT acceptance_sequence FROM coupon_application WHERE event_id=$event_id) b ON a.acceptance_sequence=b.acceptance_sequence WHERE FALSE;" > "$run_dir/consistency.tsv"
  mysql_exec "SELECT member_id, acceptance_sequence, status, public_request_id FROM coupon_application WHERE event_id=$event_id ORDER BY acceptance_sequence" > "$run_dir/applications.tsv"
  mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_id AND acceptance_sequence <> (SELECT COUNT(*) FROM coupon_application a2 WHERE a2.event_id=$event_id AND a2.acceptance_sequence <= coupon_application.acceptance_sequence)" > "$run_dir/non_contiguous_sequence_count.txt"
  date -u +%Y-%m-%dT%H:%M:%SZ > "$run_dir/finished-at-utc.txt"
}

# 워밍업은 별도 행사·별도 회원 범위로 실행하며 이후 결과 표에서 제외한다.
warmup_event=$(create_event "warmup-$(date -u +%s)")
run_one "warmup-$(date -u +%Y%m%dT%H%M%SZ)" warmup "$warmup_event" 600000000 100 2s

for attempt in $(seq 1 "$run_count"); do
  run_id="$run_label-$(date -u +%Y%m%dT%H%M%SZ)-$attempt"
  event_id=$(create_event "$run_id")
  run_one "$run_id" measurement "$event_id" "$((700000000 + attempt * 100000))" "$rate" "$duration"
done

echo "runs=$run_root"
