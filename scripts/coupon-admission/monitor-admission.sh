#!/usr/bin/env bash
set -euo pipefail

# Usage: monitor-admission.sh RUN_DIR EVENT_ID APP1_PID APP2_PID [K6_PID] [APP1_PROM] [APP2_PROM]
run_dir=$1
event_id=$2
app1_pid=$3
app2_pid=$4
k6_pid=${5:-}
app1=${6:-http://127.0.0.1:18080/actuator/prometheus}
app2=${7:-http://127.0.0.1:18081/actuator/prometheus}
container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_ADMISSION_LOADTEST_DB:-givemeticon_coupon_admission_loadtest}
mkdir -p "$run_dir/prometheus" "$run_dir/locks"

printf 'timestamp_utc,app,active,idle,total,max,pending\n' > "$run_dir/hikari.csv"
printf 'timestamp_utc,applications,pending,innodb_row_lock_waits,data_lock_wait_rows,innodb_os_log_written,innodb_data_written,innodb_data_reads,innodb_data_writes\n' > "$run_dir/mysql.csv"
printf 'timestamp_utc,app1_cpu_pct,app1_rss_kb,app2_cpu_pct,app2_rss_kb,k6_cpu_pct,k6_rss_kb,mysql_cpu_pct,mysql_mem\n' > "$run_dir/resources.csv"
printf 'timestamp_utc,app1_ygc,app1_ygct,app1_fgc,app1_fgct,app1_gct,app2_ygc,app2_ygct,app2_fgc,app2_fgct,app2_gct\n' > "$run_dir/gc.csv"

metric_value() {
  local file=$1 metric=$2
  awk -v name="$metric" '$1 ~ ("^" name "(\\{| )") {sum += $NF} END {print sum + 0}' "$file"
}

sample_app() {
  local label=$1 url=$2 timestamp=$3
  local file="$run_dir/prometheus/${label}-${timestamp}.prom"
  curl --silent --show-error --fail --max-time 1 "$url" > "$file" 2>>"$run_dir/monitor-errors.log" || return 0
  printf '%s,%s,%s,%s,%s,%s,%s\n' "$timestamp" "$label" \
    "$(metric_value "$file" hikaricp_connections_active)" \
    "$(metric_value "$file" hikaricp_connections_idle)" \
    "$(metric_value "$file" hikaricp_connections)" \
    "$(metric_value "$file" hikaricp_connections_max)" \
    "$(metric_value "$file" hikaricp_connections_pending)" >> "$run_dir/hikari.csv"
}

ps_value() {
  local pid=$1 field=$2
  ps -o "$field=" -p "$pid" 2>/dev/null | tr -d ' '
}

gc_values() {
  local pid=$1
  # jstat은 JVM 내부의 누적 GC 횟수/시간을 보며, 샘플 간 차이로 해석한다.
  jstat -gcutil "$pid" 1 1 2>/dev/null | tail -1 | awk '{printf "%s,%s,%s,%s,%s", $7,$8,$9,$10,$13}'
}

while true; do
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  sample_app app1 "$app1" "$timestamp"
  sample_app app2 "$app2" "$timestamp"

  docker exec "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -Nse \"SELECT COUNT(*), SUM(status='PENDING') FROM $database.coupon_application WHERE event_id=$event_id; SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'; SELECT COUNT(*) FROM performance_schema.data_lock_waits; SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_os_log_written'; SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_data_written'; SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_data_reads'; SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_data_writes';\"" \
    > "$run_dir/.mysql-sample" 2>>"$run_dir/monitor-errors.log" || true
  if [[ -s "$run_dir/.mysql-sample" ]]; then
    values=($(cat "$run_dir/.mysql-sample"))
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$timestamp" "${values[0]:-}" "${values[1]:-}" "${values[2]:-}" "${values[3]:-}" "${values[4]:-}" "${values[5]:-}" "${values[6]:-}" "${values[7]:-}" >> "$run_dir/mysql.csv"
  fi
  docker exec "$container" sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --batch --skip-column-names -e "SELECT NOW(6), waiting_pid, blocking_pid, locked_table, locked_index, wait_age_secs, waiting_query, blocking_query FROM sys.innodb_lock_waits;"' \
    > "$run_dir/locks/${timestamp}.tsv" 2>>"$run_dir/monitor-errors.log" || true

  stats=$(docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' "$container" 2>/dev/null || true)
  mysql_cpu=$(printf '%s' "$stats" | awk -F, '{gsub("%","",$2); print $2}')
  mysql_mem=$(printf '%s' "$stats" | awk -F, '{print $3}')
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$timestamp" \
    "$(ps_value "$app1_pid" %cpu)" "$(ps_value "$app1_pid" rss)" \
    "$(ps_value "$app2_pid" %cpu)" "$(ps_value "$app2_pid" rss)" \
    "$(ps_value "$k6_pid" %cpu)" "$(ps_value "$k6_pid" rss)" \
    "$mysql_cpu" "$mysql_mem" >> "$run_dir/resources.csv"
  printf '%s,%s,%s\n' "$timestamp" "$(gc_values "$app1_pid")" "$(gc_values "$app2_pid")" >> "$run_dir/gc.csv"
  sleep 1
done
