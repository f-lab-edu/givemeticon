#!/usr/bin/env bash
set -euo pipefail
# 사전 거부 가드: 계획한 전체 컨테이너 자원(앱 2 + DB + Redis 2 + 컨테이너 생성기 + 여유분)이 Docker VM 가용 메모리를
# 넘으면 exit 3 으로 기동을 거부한다. 무관 컨테이너 보호를 "보장"하는 장치가 아니라 계획 예산 대비 사전 점검이다.
# 입력(환경변수, run-capped-v1.sh 와 동일 이름/기본값): APP_MEM DB_MEM REDIS_MEM K6_MODE K6_MEM HEADROOM_MIB(기본 768)
# 가용 메모리: VERIFIER_VM_AVAILABLE_MIB 가 있으면 그 값(테스트/대체 실행), 없으면 짧은 alpine 컨테이너로 VM 의 MemAvailable 을 읽는다.
to_mib() { # 640m / 1g / 1536M 만 허용(단위 필수)
  local v=$1
  v=${v%[iI][bB]}   # 1400MiB -> 1400M
  case "$v" in
    *[mM]) echo "${v%[mM]}" ;;
    *[gG]) echo $(( ${v%[gG]} * 1024 )) ;;
    *) echo "invalid memory size (unit required): $v" >&2; return 2 ;;
  esac
}
app=$(to_mib "${APP_MEM:-640m}"); db=$(to_mib "${DB_MEM:-768m}"); redis=$(to_mib "${REDIS_MEM:-48m}")
k6=0; [[ "${K6_MODE:-host}" == container ]] && k6=$(to_mib "${K6_MEM:-1536m}")
headroom=${HEADROOM_MIB:-768}
planned=$(( app * 2 + db + redis * 2 + k6 ))
required=$(( planned + headroom ))
if [[ -n "${VERIFIER_VM_AVAILABLE_MIB:-}" ]]; then
  available=$VERIFIER_VM_AVAILABLE_MIB; source=override
else
  docker_os=${VERIFIER_DOCKER_OS:-$(docker info --format '{{.OperatingSystem}}' 2>/dev/null || echo unknown)}
  if [[ "$docker_os" == *"Docker Desktop"* ]]; then   # macOS/Windows: 컨테이너는 별도 VM 에서 돈다 -> VM 의 MemAvailable
    available=$(docker run --rm --memory 16m alpine awk '/^MemAvailable:/ {print int($2/1024)}' /proc/meminfo); source=docker-vm-meminfo
  else                                                # 네이티브 Linux(예: GitHub hosted runner): Docker 가 호스트 커널을 직접 쓴다 -> 호스트 MemAvailable
    available=$(awk '/^MemAvailable:/ {print int($2/1024)}' "${VERIFIER_PROC_MEMINFO:-/proc/meminfo}"); source=native-linux-host-meminfo
  fi
fi
echo "memory-guard: planned=${planned}MiB (app 2x${app} db ${db} redis 2x${redis} k6-container ${k6}) headroom=${headroom}MiB required=${required}MiB available=${available}MiB source=$source"
# 범위 고지: 이 가드는 Docker VM 메모리만 본다. macOS 호스트 전체 메모리(IDE/브라우저/타 JVM), host 생성기(K6_GOMEMLIMIT는 soft 제한),
# observer(fast-sampler/docker stats/monitor) 예산은 이 합산에 없다. 따라서 가드 통과 != 전체 안전. 아래는 참고 수치이며 강제하지 않는다.
host_total=$( (sysctl -n hw.memsize 2>/dev/null | awk '{printf "%d", $1/1048576}') || true)
[[ -n "$host_total" ]] || host_total=$(awk '/^MemTotal:/ {print int($2/1024)}' "${VERIFIER_PROC_MEMINFO:-/proc/meminfo}" 2>/dev/null || true)
k6_host=0; if [[ "${K6_MODE:-host}" == host ]]; then k6_host=$(to_mib "${K6_GOMEMLIMIT:-1400MiB}" 2>/dev/null || echo unknown); fi
echo "memory-guard: scope=docker-vm-only; NOT counted: host total ${host_total:-unknown}MiB usage, host k6 soft limit ${k6_host}MiB(K6_MODE=${K6_MODE:-host}), observers (fast-sampler/docker stats/monitors ~${OBSERVER_MIB:-256}MiB assumed), IDE/browser/other JVMs. Passing this guard is NOT a safety guarantee."
if (( available < required )); then
  echo "memory-guard: REFUSED (available < required); no containers started" >&2
  exit 3
fi
echo "memory-guard: OK"
