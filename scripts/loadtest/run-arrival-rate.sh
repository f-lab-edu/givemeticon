#!/usr/bin/env bash
set -euo pipefail

# 한 run은 새 coupon_stock 행을 사용한다. 운영 DB 삭제나 기존 테스트 행 초기화는 하지 않는다.
root_dir=$(cd "$(dirname "$0")/../.." && pwd)
run_id=${RUN_ID:-"$(date -u +%Y%m%dT%H%M%SZ)-pool${LOADTEST_HIKARI_MAX:-10}-r${ISSUE_RATE:-100}"}
run_dir="$root_dir/reports/mysql-coupon-loadtest/runs/$run_id"
container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${LOADTEST_DB_NAME:-givemeticon_loadtest}
stock_total=${STOCK_TOTAL:-1000}
mkdir -p "$run_dir"

"$root_dir/scripts/loadtest/setup-isolated-db.sh" > "$run_dir/environment-db.txt"
stock_id=$(docker exec "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -Nse 'INSERT INTO $database.coupon_stock(total, remain) VALUES ($stock_total, $stock_total); SELECT LAST_INSERT_ID();'" | tail -1)

{
  echo "run_id=$run_id"
  echo "started_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git_commit=$(git -C "$root_dir" rev-parse HEAD)"
  echo "git_status=$(git -C "$root_dir" status --short | wc -l | tr -d ' ') changed_files"
  echo "stock_id=$stock_id"
  echo "stock_total=$stock_total"
  echo "issue_rate=${ISSUE_RATE:-100}"
  echo "duration=${DURATION:-10s}"
  echo "hikari_max=${LOADTEST_HIKARI_MAX:-10} per_app_configured"
  echo "app_instances=2"
  echo "configured_connection_budget=$(( ${LOADTEST_HIKARI_MAX:-10} * 2 ))"
  echo "lock_mode=${LOCK_MODE:-mysql-only}"
  echo "worker_mode=${WORKER_MODE:-off}"
  echo "batch_size=${BATCH_SIZE:-}"
  echo "k6_mode=${MODE:-sync}"
  java -version 2>&1 | head -1
  docker exec "$container" sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "SELECT VERSION(), @@transaction_isolation, @@max_connections"' 2>&1
} > "$run_dir/run.env"

STOCK_ID_FOR_MONITOR="$stock_id" LOADTEST_DB_NAME="$database" "$root_dir/scripts/loadtest/monitor.sh" "$run_dir" &
monitor_pid=$!
cleanup() { kill "$monitor_pid" 2>/dev/null || true; }
trap cleanup EXIT

RUN_ID="$run_id" STOCK_ID="$stock_id" \
  docker run --rm -v "$root_dir/scripts/k6:/scripts:ro" -v "$run_dir:/results" \
  -e RUN_ID -e STOCK_ID -e ISSUE_RATE="${ISSUE_RATE:-100}" -e DURATION="${DURATION:-10s}" \
  -e PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-200}" -e MAX_VUS="${MAX_VUS:-2000}" \
  -e MODE="${MODE:-sync}" \
  -e TARGETS="${TARGETS:-http://host.docker.internal:8082,http://host.docker.internal:8083}" \
  grafana/k6 run --summary-export /results/k6-summary.json /scripts/coupon-arrival-rate.js \
  | tee "$run_dir/k6-console.log"

# k6가 끝나도 서버 쪽에는 아직 처리되지 않은 요청이 남아있을 수 있다 - 비동기/묶음 발급이면
# PENDING 원장으로 보이고, 동기 경로도 재고별 분산 락·Hikari 풀 경합이 심하면 Tomcat 스레드
# 큐에 걸린 채 DB에는 아직 한 줄도 안 쓰인 요청이 남는다(PENDING=0으로는 안 잡힌다). 그래서
# "이 stock_id의 전체 건수가 더 늘지 않는지"까지 함께 본다 - 아직 유입 중인 요청을 "끝났다"고
# 오판하면 뒤이은 run이 겹쳐 실행되며 서로 다른 stock_id의 접수가 같은 시간대에 섞인다(실제로
# 재현된 문제). "정상 시 최종 결과 3분 이내" 목표에 맞춰 최대 180초까지 기다린다.
drain_deadline=$((SECONDS + 180))
prev_count=-1
stable_ticks=0
count=""; pending=""
while [[ $SECONDS -lt $drain_deadline ]]; do
  count=$(docker exec "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -Nse 'SELECT COUNT(*) FROM $database.coupon_issue_request WHERE stock_id=$stock_id'" 2>/dev/null | tail -1)
  pending=$(docker exec "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -Nse 'SELECT COUNT(*) FROM $database.coupon_issue_request WHERE stock_id=$stock_id AND status=\"PENDING\"'" 2>/dev/null | tail -1)
  if [[ "$count" == "$prev_count" && "$pending" == "0" ]]; then
    stable_ticks=$((stable_ticks + 1))
    if [[ $stable_ticks -ge 3 ]]; then
      break
    fi
  else
    stable_ticks=0
  fi
  prev_count=$count
  sleep 1
done
echo "drain_wait_seconds_used=$SECONDS request_count_at_drain_end=${count:-unknown} pending_at_drain_end=${pending:-unknown}" >> "$run_dir/run.env"

verify_sql=${VERIFY_SQL:-"$root_dir/scripts/loadtest/verify-event.sql"}
{ printf 'SET @stock_id=%s;\n' "$stock_id"; printf 'SET @stock_total=%s;\n' "$stock_total"; sed -n '2,$p' "$verify_sql"; } \
  | docker exec -i "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --batch --skip-column-names $database" \
  > "$run_dir/consistency.txt"
date -u +%Y-%m-%dT%H:%M:%SZ > "$run_dir/finished-at-utc.txt"
echo "$run_id"
