#!/usr/bin/env bash
set -uo pipefail
# 대체 실행 검증(컨테이너 기동 없음): VERIFIER_VM_AVAILABLE_MIB override 로 거부/통과/단위/k6 컨테이너 합산을 확인한다.
g="$(cd "$(dirname "$0")" && pwd)/memory-guard.sh"; fail=0
check() { # name expected_exit env...
  local name=$1 want=$2; shift 2
  env -u VERIFIER_VM_AVAILABLE_MIB "$@" "$g" >/tmp/mg.out 2>&1; local got=$?
  if [[ $got -eq $want ]]; then echo "ok   $name (exit $got)"; else echo "FAIL $name want=$want got=$got"; cat /tmp/mg.out; fail=1; fi
}
# 기본 계획: 640*2+768+48*2 = 2144, +768 = 2912
check "refuses at observed 1449MiB"       3 VERIFIER_VM_AVAILABLE_MIB=1449
check "refuses just below required"       3 VERIFIER_VM_AVAILABLE_MIB=2911
check "passes at exactly required"        0 VERIFIER_VM_AVAILABLE_MIB=2912
check "minimal plan refuses at 1449"      3 VERIFIER_VM_AVAILABLE_MIB=1449 APP_MEM=512m DB_MEM=1g REDIS_MEM=32m   # 1024+1024+64+768=2880
check "units g/m parsed (1g db)"          0 VERIFIER_VM_AVAILABLE_MIB=2880 APP_MEM=512m DB_MEM=1g REDIS_MEM=32m
check "k6 container adds its memory"      3 VERIFIER_VM_AVAILABLE_MIB=2912 K6_MODE=container K6_MEM=1536m
check "k6 container passes when covered"  0 VERIFIER_VM_AVAILABLE_MIB=$((2912+1536)) K6_MODE=container K6_MEM=1536m
check "host k6 not counted"               0 VERIFIER_VM_AVAILABLE_MIB=2912 K6_MODE=host K6_MEM=1536m
check "unit required"                     2 VERIFIER_VM_AVAILABLE_MIB=99999 APP_MEM=640
check "headroom override honoured"        3 VERIFIER_VM_AVAILABLE_MIB=2912 HEADROOM_MIB=1000
# 네이티브 Linux(hosted runner): Docker Desktop 이 아니면 호스트 /proc/meminfo 의 MemAvailable 을 쓴다(컨테이너 기동 없음)
tmpm=$(mktemp); printf 'MemTotal: 16000000 kB\nMemAvailable: 3000000 kB\n' > "$tmpm"      # 3000000kB = 2929MiB >= 2912
check "native linux passes from /proc/meminfo"   0 VERIFIER_DOCKER_OS="Ubuntu 22.04.5 LTS" VERIFIER_PROC_MEMINFO="$tmpm"
printf 'MemTotal: 16000000 kB\nMemAvailable: 2900000 kB\n' > "$tmpm"      # 2832MiB < 2912
check "native linux refuses below required"       3 VERIFIER_DOCKER_OS="Ubuntu 22.04.5 LTS" VERIFIER_PROC_MEMINFO="$tmpm"
grep -q 'source=native-linux-host-meminfo' /tmp/mg.out && echo "ok   native linux source reported" || { echo "FAIL native source label"; fail=1; }
rm -f "$tmpm"
exit $fail
