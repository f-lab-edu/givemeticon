#!/usr/bin/env bash
set -euo pipefail

# k6 프로세스 자체의 RSS·CPU·가상메모리와 호스트 여유 메모리·스왑 사용량을 ps/vm_stat/sysctl만으로
# 기록한다(도커·MySQL 의존 없음 - monitor-admission.sh는 호스트가 무거워지면 그 자신도 느려져
# 표본이 비는 문제가 있었다).
# Usage: monitor-generator.sh RUN_DIR K6_PID [INTERVAL_SECONDS]
run_dir=$1
k6_pid=$2
interval=${3:-0.5}

out="$run_dir/generator-resources.csv"
printf 'timestamp_utc,k6_rss_kb,k6_cpu_pct,k6_vsz_kb,host_free_mem_mb,host_swap_used_mb\n' > "$out"

page_size=$(sysctl -n hw.pagesize 2>/dev/null || echo 16384)

host_free_mem_mb() {
  local free_pages
  free_pages=$(vm_stat 2>/dev/null | awk '/Pages free/ {gsub("\\.", "", $3); print $3}')
  if [[ -z "$free_pages" ]]; then echo ""; return; fi
  echo $(( free_pages * page_size / 1024 / 1024 ))
}

host_swap_used_mb() {
  sysctl vm.swapusage 2>/dev/null | awk -F'used = ' '{print $2}' | awk '{print $1}' | tr -d 'M'
}

while kill -0 "$k6_pid" 2>/dev/null; do
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
  rss_kb=$(ps -o rss= -p "$k6_pid" 2>/dev/null | tr -d ' ')
  cpu_pct=$(ps -o %cpu= -p "$k6_pid" 2>/dev/null | tr -d ' ')
  vsz_kb=$(ps -o vsz= -p "$k6_pid" 2>/dev/null | tr -d ' ')
  free_mb=$(host_free_mem_mb)
  swap_mb=$(host_swap_used_mb)
  printf '%s,%s,%s,%s,%s,%s\n' "$timestamp" "${rss_kb:-}" "${cpu_pct:-}" "${vsz_kb:-}" "${free_mb:-}" "${swap_mb:-}" >> "$out"
  sleep "$interval"
done
