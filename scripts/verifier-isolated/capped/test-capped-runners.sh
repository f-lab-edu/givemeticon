#!/usr/bin/env bash
set -uo pipefail
# 대체 실행 검증(실제 docker/curl/lsof 를 쓰지 않음, 컨테이너·부하 없음): stub 이 인자를 기록하고 runner 동작을 검사한다.
# /bin/bash(macOS 3.2)로 runner 를 직접 실행해 빈 배열 + set -u 회귀도 함께 본다.
here="$(cd "$(dirname "$0")" && pwd)"; tmp=$(mktemp -d); fail=0
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"; echo "SENTINEL_PW_9f3a1c" > "$tmp/pw"
cat > "$tmp/bin/docker" <<STUB
#!/usr/bin/env bash
envnote=""; [ -n "\${MYSQL_ROOT_PASSWORD:-}" ] && envnote=" [env:MYSQL_ROOT_PASSWORD set]"
[ -n "\${SPRING_DATASOURCE_PASSWORD:-}\${LOADTEST_DB_PASSWORD:-}" ] && envnote="\$envnote [env:app-db-password set]"
echo "docker \$*\$envnote" >> "$tmp/docker.log"
case "\$1" in
  run)
    n=\$(( \$(cat "$tmp/runcount" 2>/dev/null || echo 0) + 1 )); echo "\$n" > "$tmp/runcount"
    if [ "\${STUB_FAIL_RUN:-0}" = "\$n" ]; then echo "stub: docker run failed" >&2; exit 125; fi
    echo "id-\$n" ;;
  logs) echo "ready for connections. Version: x  socket: y  port: 3306  MySQL Community" ;;
  inspect)
    case "\$*" in
      *Labels*) echo "\${STUB_LABEL:-capped-harness}" ;;
      *State.Running*) echo "\${STUB_RUNNING:-false}" ;;
      *) [ "\${STUB_EXISTS:-0}" = 1 ] || exit 1 ;;
    esac ;;
  network) if [ "\$2" = inspect ]; then
    [ "\${STUB_EXISTS:-0}" = 1 ] || exit 1
    case "\$*" in *Labels*) echo "\${STUB_LABEL:-capped-harness}" ;; esac
  fi ;;
esac
exit 0
STUB
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/bin/curl"; printf '#!/usr/bin/env bash\nexit 1\n' > "$tmp/bin/lsof"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/bin/k6"; chmod +x "$tmp"/bin/*
export PATH="$tmp/bin:$PATH" VERIFIER_PW_FILE="$tmp/pw" SKIP_BUILD=true K6_MODE=container VERIFIER_VM_AVAILABLE_MIB=99999
ok() { echo "ok   $1"; }; bad() { echo "FAIL $1"; fail=1; }
reset() { : > "$tmp/docker.log"; rm -f "$tmp/runcount"; }
runit() { /bin/bash "$here/$1" "$2" > "$tmp/out" 2>&1; echo $?; }

for runner in run-capped-v1.sh run-capped-stock.sh; do
  reset; rc=$(VERIFIER_VM_AVAILABLE_MIB=1449 runit $runner up)
  [[ $rc -eq 3 && ! -s "$tmp/docker.log" ]] && ok "$runner: guard refusal exit 3, 0 docker calls" || bad "$runner guard rc=$rc"

  reset; rc=$(runit $runner up)
  [[ $rc -eq 0 ]] && ok "$runner: up exits 0 under stubs" || { bad "$runner up rc=$rc"; cat "$tmp/out"; }
  [[ $(grep -c '^docker run -d' "$tmp/docker.log") -eq 5 ]] && ok "$runner: starts exactly 5 containers" || bad "$runner container count"
  [[ $(grep '^docker run -d' "$tmp/docker.log" | grep -c -e '--oom-score-adj=1000') -eq 5 && $(grep '^docker run -d' "$tmp/docker.log" | grep -vc -e '--memory-swap') -eq 0 ]] && ok "$runner: all containers oom-score+memory caps" || bad "$runner caps"
  grep -q 'SENTINEL_PW_9f3a1c' "$tmp/docker.log" && bad "$runner leaked password into docker argv" || ok "$runner: password not in docker argv"
  grep 'run -d.*mysql' "$tmp/docker.log" | grep -q 'env:MYSQL_ROOT_PASSWORD set' && grep 'run -d.*app1' "$tmp/docker.log" | grep -q 'env:app-db-password set' && ok "$runner: secrets reach docker only via env" || bad "$runner env passing"
  grep -E 'givemeticon-(mysql|redis|kafka)|-p (3306|6379|6380|9092):' "$tmp/docker.log" >/dev/null && bad "$runner references shared infra" || ok "$runner: no shared infra reference"
  grep -q 'network create --label xyz.buzz.verifier.managed=capped-harness verifier-cap-net' "$tmp/docker.log" && ok "$runner: own labeled network" || bad "$runner network"

  reset; rc=$(STUB_EXISTS=1 runit $runner up)
  [[ $rc -eq 2 ]] && ! grep -q '^docker run' "$tmp/docker.log" && grep -q 'already exists' "$tmp/out" && ok "$runner: existing name refused, no run" || bad "$runner existing rc=$rc"

  reset; rc=$(STUB_FAIL_RUN=1 runit $runner up)
  [[ $rc -eq 125 ]] && ! grep -q 'unbound variable' "$tmp/out" && ! grep -q '^docker stop' "$tmp/docker.log" && ok "$runner: first run failure keeps exit 125 (bash 3.2, no unbound)" || { bad "$runner first-run failure rc=$rc"; cat "$tmp/out"; }

  reset; rc=$(STUB_FAIL_RUN=3 runit $runner up)
  [[ $rc -eq 125 && $(grep -c '^docker stop' "$tmp/docker.log") -eq 2 ]] && grep -q '^docker stop id-1' "$tmp/docker.log" && grep -q '^docker stop id-2' "$tmp/docker.log" && ! grep -q '^docker rm' "$tmp/docker.log" && ok "$runner: partial up stops only created ids, keeps exit 125, no rm" || { bad "$runner partial rc=$rc"; cat "$tmp/docker.log" | tail -5; }

  reset; rc=$(STUB_FAIL_RUN=2 STUB_LABEL=someone-else runit $runner up)
  [[ $rc -eq 125 ]] && ! grep -q '^docker stop' "$tmp/docker.log" && grep -q 'cleanup ownership mismatch' "$tmp/out" && ok "$runner: partial up with ownership mismatch stops nothing" || bad "$runner mismatch rc=$rc"

  reset; rc=$(STUB_EXISTS=1 VERIFIER_OWNER_ID=expected-owner runit $runner down)
  [[ $rc -eq 2 ]] && ! grep -qE '^docker (stop|rm)' "$tmp/docker.log" && ok "$runner: owner mismatch changes nothing" || bad "$runner owner mismatch rc=$rc"

  reset; rc=$(STUB_EXISTS=1 runit $runner down)
  [[ $rc -eq 0 ]] && grep -q '^docker stop verifier-cap-app1' "$tmp/docker.log" && ! grep -qE '^docker (rm|network rm)' "$tmp/docker.log" && ok "$runner: down is stop-only (no rm, network kept)" || bad "$runner down rc=$rc"

  reset; rc=$(STUB_EXISTS=1 STUB_LABEL=someone-else runit $runner down)
  [[ $rc -eq 2 ]] && ! grep -qE '^docker (stop|rm)' "$tmp/docker.log" && ok "$runner: down with ownership mismatch changes nothing" || bad "$runner down mismatch rc=$rc"

  reset; rc=$(STUB_EXISTS=1 STUB_RUNNING=true runit $runner remove)
  [[ $rc -eq 2 ]] && ! grep -qE '^docker rm' "$tmp/docker.log" && grep -q 'stop container before removal' "$tmp/out" && ok "$runner: remove refuses running containers" || bad "$runner remove running rc=$rc"

  reset; rc=$(STUB_EXISTS=1 STUB_RUNNING=false runit $runner remove)
  [[ $rc -eq 0 && $(grep -c '^docker rm verifier-cap' "$tmp/docker.log") -eq 5 ]] && ! grep -q 'rm -f\|--force' "$tmp/docker.log" && ok "$runner: remove deletes stopped owned containers without force" || bad "$runner remove rc=$rc"

  reset; rc=$(STUB_EXISTS=1 STUB_RUNNING=false STUB_LABEL=someone-else runit $runner remove)
  [[ $rc -eq 2 ]] && ! grep -qE '^docker rm' "$tmp/docker.log" && ok "$runner: remove with ownership mismatch changes nothing" || bad "$runner remove mismatch rc=$rc"
done

# lsof 가 없는 Linux: ss 로 포트 사용 여부를 판단한다(사용 중이면 거부, 비었으면 통과)
mv "$tmp/bin/lsof" "$tmp/bin/lsof.off"
printf '#!/usr/bin/env bash\necho "State Recv-Q Send-Q Local Address:Port"\n' > "$tmp/bin/ss"; chmod +x "$tmp/bin/ss"
reset; rc=$(PATH="$tmp/bin:/usr/bin:/bin" runit run-capped-v1.sh up)
[[ $rc -eq 0 ]] && ok "no lsof + ss(empty): ports considered free" || bad "ss-free rc=$rc"
printf '#!/usr/bin/env bash\necho "State Recv-Q Send-Q Local Address:Port"; echo "LISTEN 0 4096 0.0.0.0:3307 0.0.0.0:*"\n' > "$tmp/bin/ss"
reset; rc=$(PATH="$tmp/bin:/usr/bin:/bin" runit run-capped-v1.sh up)
[[ $rc -eq 2 ]] && ! grep -q '^docker run' "$tmp/docker.log" && ok "no lsof + ss(busy): port in use refused" || bad "ss-busy rc=$rc"
rm -f "$tmp/bin/ss"; mv "$tmp/bin/lsof.off" "$tmp/bin/lsof"
reset; runit run-capped-stock.sh up >/dev/null
grep '^docker run -d' "$tmp/docker.log" | grep 'app1' | grep -q 'mysql-loadtest,redis-lock-loadtest' && ok "stock: lock profile active" || bad "stock profile"
grep '^docker run -d' "$tmp/docker.log" | grep 'app1' | grep -q 'spring.data.redis.mail.host=verifier-cap-redis-mail' && grep '^docker run -d' "$tmp/docker.log" | grep 'app1' | grep -q 'spring.data.redis.coupon.host=verifier-cap-redis-coupon' && ok "stock: redis overridden to isolated hosts" || bad "stock redis override"
grep '^docker run -d' "$tmp/docker.log" | grep 'app1' | grep -q 'bootstrap.server=localhost:19092' && ok "stock: kafka overridden (not 9092)" || bad "stock kafka override"
grep '^docker run -d' "$tmp/docker.log" | grep 'app1' | grep -q 'LOADTEST_DB_URL=jdbc:mysql://verifier-cap-mysql:3306/' && ok "stock: JDBC points at isolated mysql" || bad "stock jdbc"
exit $fail
