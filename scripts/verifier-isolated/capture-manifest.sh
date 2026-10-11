#!/usr/bin/env bash
# 실행 직전/직후 자원·공존 부하 manifest. 사용: capture-manifest.sh <out_file>
out=$1
{
  echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git_head=$(git rev-parse HEAD) dirty_files=$(git status --short | wc -l | tr -d ' ')"
  if [[ $(uname -s) == Darwin ]]; then
    echo "host: cpu=$(sysctl -n hw.ncpu) mem_bytes=$(sysctl -n hw.memsize)"
    sysctl vm.swapusage
  else
    echo "host: cpu=$(nproc)"
    awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/' /proc/meminfo
  fi
  uptime
  echo "--- docker VM"; docker info --format 'docker_cpus={{.NCPU}} docker_mem={{.MemTotal}}'
  echo "--- containers (name cpu% mem)"; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'
  echo "--- container limits (cpus/memory; 0 = unlimited)"
  for c in $(docker ps --format '{{.Names}}'); do docker inspect "$c" --format '{{.Name}} nano_cpus={{.HostConfig.NanoCpus}} memory={{.HostConfig.Memory}}'; done
  echo "--- top host processes by CPU"; { ps -Ao pcpu,rss,pid,comm -r 2>/dev/null || ps -Ao pcpu,rss,pid,comm --sort=-pcpu; } | head -12
  echo "--- other java/gradle processes"; ps -Ao pid,pcpu,rss,comm | awk 'NR == 1 || $4 ~ /(^|\/)(java|gradle)$/ {print}'
  echo "--- listening ports of interest"; { if command -v lsof >/dev/null; then lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | awk '{print $1,$2,$9}'; else ss -ltn; fi; } | grep -E ':(3306|3307|6379|6380|16379|16380|9092|1808[01]|1810[01])\b' || true
} > "$out" 2>&1
