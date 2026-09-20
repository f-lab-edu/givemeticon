#!/usr/bin/env bash
set -uo pipefail

# Usage: monitor-cpu.sh RUN_DIR JVM1_PID JVM2_PID
# 진단 전용 사이드카 - 기존 monitor.sh(HikariCP/MySQL 락)와 별개로, 프로세스별 CPU를
# 1초 간격으로 샘플링한다. 애플리케이션 코드나 동작을 바꾸지 않는다(순수 관찰).
run_dir=$1
jvm1_pid=${2:-}
jvm2_pid=${3:-}
mkdir -p "$run_dir"
out="$run_dir/process-cpu.csv"
printf 'timestamp_utc,jvm1_cpu_pct,jvm2_cpu_pct,mysql_cpu_pct,redis_coupon_cpu_pct,k6_cpu_pct\n' > "$out"

jvm_cpu() {
  local pid=$1
  [[ -z "$pid" ]] && { echo ""; return; }
  ps -o %cpu= -p "$pid" 2>/dev/null | tr -d ' '
}

# 컨테이너별로 docker stats를 따로 부르면 각 호출이 1~2초씩 걸려 1초 간격을 못 지킨다.
# 한 번의 호출로 모든 컨테이너(+그 순간 떠 있는 k6 컨테이너)를 같이 샘플링한다.
while true; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  jvm1=$(jvm_cpu "$jvm1_pid")
  jvm2=$(jvm_cpu "$jvm2_pid")
  k6_name=$(docker ps --filter ancestor=grafana/k6 --format '{{.Names}}' | head -1)
  names="givemeticon-mysql givemeticon-redis-coupon"
  [[ -n "$k6_name" ]] && names="$names $k6_name"
  stats=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' $names 2>/dev/null)
  mysql_cpu=$(echo "$stats" | awk '$1=="givemeticon-mysql"{gsub("%","",$2);print $2}')
  redis_cpu=$(echo "$stats" | awk '$1=="givemeticon-redis-coupon"{gsub("%","",$2);print $2}')
  k6_cpu=""
  [[ -n "$k6_name" ]] && k6_cpu=$(echo "$stats" | awk -v n="$k6_name" '$1==n{gsub("%","",$2);print $2}')
  printf '%s,%s,%s,%s,%s,%s\n' "$ts" "$jvm1" "$jvm2" "$mysql_cpu" "$redis_cpu" "$k6_cpu" >> "$out"
  # docker stats --no-stream 자체가 컨테이너 수와 무관하게 ~2초 걸린다(한 사이클 대기) -
  # 추가 sleep 없이 바로 다음 루프를 돌려도 실제 간격은 이미 ~2초다.
done
