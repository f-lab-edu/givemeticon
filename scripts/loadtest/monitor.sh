#!/usr/bin/env bash
set -euo pipefail

# Usage: monitor.sh RUN_DIR APP1_METRICS APP2_METRICS
run_dir=$1
app1=${2:-http://127.0.0.1:8082/actuator/prometheus}
app2=${3:-http://127.0.0.1:8083/actuator/prometheus}
container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${LOADTEST_DB_NAME:-givemeticon_loadtest}
stock_id=${STOCK_ID_FOR_MONITOR:-}
mkdir -p "$run_dir/prometheus"
printf 'timestamp_utc,app,active,idle,total,max,pending\n' > "$run_dir/hikari.csv"
# lock_wait_rows는 순간 표본(data_lock_waits 행 수), innodb_lock_waits는 누적값이다.
# pending_requests는 이 run의 stock_id에 대해 아직 ISSUED/REJECTED로 확정되지 않은
# coupon_issue_request 건수(=미처리 건수)다. STOCK_ID_FOR_MONITOR가 없으면 빈 값으로 남긴다.
printf 'timestamp_utc,innodb_lock_waits,lock_wait_rows,pending_requests\n' > "$run_dir/mysql-locks.csv"

metric_value() {
  local file=$1 metric=$2
  awk -v name="$metric" '$1 ~ ("^" name "(\{| )") {sum += $NF} END {if (NR == 0) print ""; else print sum + 0}' "$file"
}

sample_app() {
  local label=$1
  local url=$2
  local timestamp=$3
  local file="$run_dir/prometheus/${label}-${timestamp}.prom"
  curl --fail --silent --show-error --max-time 1 "$url" > "$file" 2>>"$run_dir/monitor-errors.log" || return 0
  local active idle total max pending
  active=$(metric_value "$file" hikaricp_connections_active)
  idle=$(metric_value "$file" hikaricp_connections_idle)
  total=$(metric_value "$file" hikaricp_connections)
  max=$(metric_value "$file" hikaricp_connections_max)
  pending=$(metric_value "$file" hikaricp_connections_pending)
  printf '%s,%s,%s,%s,%s,%s,%s\n' "$timestamp" "$label" "$active" "$idle" "$total" "$max" "$pending" >> "$run_dir/hikari.csv"
}

while true; do
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  sample_app app1 "$app1" "$timestamp"
  sample_app app2 "$app2" "$timestamp"
  # 세 값을 한 번의 docker exec/mysql 호출로 묶어 폴링 왕복을 줄인다(각 docker exec는
  # 컨테이너 안에 새 프로세스를 띄우는 비용이 있어, 별도 호출로 나누면 1초 간격을 지키기
  # 어렵다). data_lock_waits는 순간 표본, Innodb_row_lock_waits는 누적값 - 둘을 같은
  # 의미로 합치지 않는다. pending_requests는 stock_id가 주어졌을 때만 채워진다.
  pending_query="SELECT NULL"
  if [[ -n "$stock_id" ]]; then
    pending_query="SELECT COUNT(*) FROM $database.coupon_issue_request WHERE stock_id = $stock_id AND status = 'PENDING'"
  fi
  docker exec "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -Nse \"SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_row_lock_waits'; SELECT COUNT(*) FROM performance_schema.data_lock_waits; $pending_query;\"" \
    > "$run_dir/.mysql-sample" 2>>"$run_dir/monitor-errors.log" || true
  if [[ -s "$run_dir/.mysql-sample" ]]; then
    lock_waits=$(sed -n '1p' "$run_dir/.mysql-sample")
    lock_wait_rows=$(sed -n '2p' "$run_dir/.mysql-sample")
    pending_requests=$(sed -n '3p' "$run_dir/.mysql-sample")
    printf '%s,%s,%s,%s\n' "$timestamp" "$lock_waits" "$lock_wait_rows" "${pending_requests:-}" >> "$run_dir/mysql-locks.csv"
  fi
  # 차단/대기 SQL은 집계 수치와 분리해 원문 TSV로 보존한다. 행이 없으면 빈 파일도 증거다.
  docker exec "$container" sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --batch --skip-column-names -e "SELECT NOW(6), waiting_pid, blocking_pid, locked_table, locked_index, wait_age_secs, waiting_query, blocking_query FROM sys.innodb_lock_waits;"' \
    > "$run_dir/mysql-lock-details-${timestamp}.tsv" 2>>"$run_dir/monitor-errors.log" || true
  sleep 1
done
