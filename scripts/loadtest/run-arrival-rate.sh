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
  -e TARGETS="${TARGETS:-http://host.docker.internal:8082,http://host.docker.internal:8083}" \
  grafana/k6 run --summary-export /results/k6-summary.json /scripts/coupon-arrival-rate.js \
  | tee "$run_dir/k6-console.log"

{ printf 'SET @stock_id=%s;\n' "$stock_id"; sed -n '2,$p' "$root_dir/scripts/loadtest/verify-event.sql"; } \
  | docker exec -i "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --batch --skip-column-names $database" \
  > "$run_dir/consistency.txt"
date -u +%Y-%m-%dT%H:%M:%SZ > "$run_dir/finished-at-utc.txt"
echo "$run_id"
