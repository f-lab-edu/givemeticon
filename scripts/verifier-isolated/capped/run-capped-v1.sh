#!/usr/bin/env bash
set -euo pipefail
# V1(event 묶음 접수+묶음 발급) 경로의 자원 제한 baseline runner. 소유 컨테이너만 만들고 정리한다.
# 앱 2개/MySQL/Redis 2개는 docker 로 띄워 --cpus/--memory 를 강제하고(검증은 inspect+cgroup), 생성기는
# K6_MODE=host(GOMAXPROCS/GOMEMLIMIT soft 제한) 또는 container(--cpus/--memory 강제) 로 선택한다.
# 모든 소유 컨테이너는 --oom-score-adj=1000 으로, VM 메모리 압박 시 무관 컨테이너보다 먼저 종료되게 한다.
# 사용: capped/run-capped-v1.sh up|run|down|remove   (down=정상 stop만, remove=소유·정지 확인 후 별도 삭제; 환경변수는 아래 기본값 참고)
repo_dir=$(cd "$(dirname "$0")/../../.." && pwd)
P=${VERIFIER_PREFIX:-verifier-cap}; NET=$P-net
APP_CPUS=${APP_CPUS:-1}; APP_MEM=${APP_MEM:-640m}; APP_XMX=${APP_XMX:-384m}
DB_CPUS=${DB_CPUS:-1.5}; DB_MEM=${DB_MEM:-768m}; DB_POOL=${DB_POOL:-192M}
REDIS_CPUS=${REDIS_CPUS:-0.25}; REDIS_MEM=${REDIS_MEM:-48m}
K6_MODE=${K6_MODE:-host}; K6_CPUS=${K6_CPUS:-2}; K6_MEM=${K6_MEM:-1536m}
K6_GOMAXPROCS=${K6_GOMAXPROCS:-2}; K6_GOMEMLIMIT=${K6_GOMEMLIMIT:-1400MiB}
PORT_A=${PORT_A:-18080}; PORT_B=${PORT_B:-18081}; DB_PORT=${DB_PORT:-3307}
DB_NAME=${DB_NAME:-givemeticon_coupon_admission_loadtest_cap}
JRE_IMAGE=${JRE_IMAGE:-eclipse-temurin:17-jre}
MYSQL_IMAGE=${MYSQL_IMAGE:-mysql:8.0}
REDIS_IMAGE=${REDIS_IMAGE:-redis:7-alpine}
K6_IMAGE=${K6_IMAGE:-grafana/k6}
OWNER=${VERIFIER_OWNER_ID:-}
owner_args=(); [[ -z "$OWNER" ]] || owner_args=(--label "xyz.buzz.verifier.owner=$OWNER")
pw_file=${VERIFIER_PW_FILE:-$HOME/.buzz/.scratch/verifier_mysql_pw}
common=(${owner_args[@]+"${owner_args[@]}"} --oom-score-adj=1000 --network "$NET" --label xyz.buzz.verifier.managed=capped-harness)

mysql_pw() { cat "$pw_file"; }
port_in_use() { # macOS: lsof, Linux: ss/netstat (lsof 가 없는 hosted runner 대응). 도구가 전혀 없으면 사용 중으로 간주(안전 쪽).
  if command -v lsof >/dev/null 2>&1; then lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v ss >/dev/null 2>&1; then ss -ltn "sport = :$1" 2>/dev/null | tail -n +2 | grep -q .
  elif command -v netstat >/dev/null 2>&1; then netstat -ltn 2>/dev/null | grep -q ":$1 "
  else return 0; fi
}
managed_label=xyz.buzz.verifier.managed
owned_names() { echo "$P-app1 $P-app2 $P-mysql $P-redis-mail $P-redis-coupon"; }
created=()
# 이번 실행에서 실제로 만든 컨테이너 ID 만 추적한다(기존/타인 컨테이너와 구분).
drun() { local id; id=$(docker run -d "$@") || return $?; created+=("$id"); }
cleanup_failed_up() { # 실패한 up 의 정리: 이번에 만든 것만, 라벨 재검증 후 정상 stop. 원래 exit 보존, 정리 실패는 보고.
  local status=$?; trap - EXIT
  if [[ $status != 0 ]]; then
    local id lbl
    # bash 3.2(macOS 기본)에서 빈 배열 + set -u 안전 순회
    for id in ${created[@]+"${created[@]}"}; do
      lbl=$(docker inspect --format '{{ index .Config.Labels "xyz.buzz.verifier.managed" }}' "$id" 2>/dev/null) || { echo "cleanup inspect failed: $id" >&2; continue; }
      if [[ "$lbl" == capped-harness ]] && owner_matches "$id"; then docker stop "$id" >/dev/null || echo "cleanup stop failed: $id" >&2
      else echo "cleanup ownership mismatch: $id" >&2; fi
    done
  fi
  exit "$status"
}
refuse_existing() { local n; for n in $(owned_names); do ! docker inspect "$n" >/dev/null 2>&1 || { echo "container already exists: $n (use explicit remove first)" >&2; exit 2; }; done
  ! docker network inspect "$NET" >/dev/null 2>&1 || { echo "network already exists: $NET" >&2; exit 2; }; }
# 비밀번호는 컨테이너 내부 env 에서만 해석한다(호스트 argv 에 값이 나타나지 않는다).
dbx() { docker exec -i "$P-mysql" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$@"' sh "$@" 2>/dev/null; }

up() {
  "$(dirname "${BASH_SOURCE[0]}")/memory-guard.sh" || exit 3   # 사전 거부: 계획 자원 + 여유분 > VM 가용이면 아무것도 기동하지 않는다
  command -v k6 >/dev/null || [[ "$K6_MODE" == container ]] || { echo 'k6 required' >&2; exit 2; }
  for p in "$PORT_A" "$PORT_B" "$DB_PORT"; do ! port_in_use "$p" || { echo "port $p in use" >&2; exit 2; }; done
  refuse_existing
  trap cleanup_failed_up EXIT
  docker network create ${owner_args[@]+"${owner_args[@]}"} --label "$managed_label=capped-harness" "$NET" >/dev/null
  MYSQL_ROOT_PASSWORD="$(mysql_pw)" drun --name "$P-mysql" "${common[@]}" --cpus "$DB_CPUS" --memory "$DB_MEM" --memory-swap "$DB_MEM" -p "$DB_PORT:3306" \
    -e MYSQL_ROOT_PASSWORD "$MYSQL_IMAGE" --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci \
    --innodb-buffer-pool-size="$DB_POOL" >/dev/null
  for n in mail coupon; do
    drun --name "$P-redis-$n" "${common[@]}" --cpus "$REDIS_CPUS" --memory "$REDIS_MEM" --memory-swap "$REDIS_MEM" "$REDIS_IMAGE" >/dev/null
  done
  for _ in $(seq 1 90); do
    docker logs "$P-mysql" 2>&1 | grep -q "ready for connections.*port: 3306  MySQL" && dbx -e 'SELECT 1' >/dev/null && break; sleep 2
  done
  dbx -e 'SELECT 1' >/dev/null || { echo "mysql not ready" >&2; exit 1; }
  dbx -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  for f in V20260920__add_coupon_event_admission_ledger V20260923__add_coupon_award V20260923_2__add_coupon_award_redemption; do
    dbx "$DB_NAME" < "$repo_dir/src/main/resources/db/migration/$f.sql"
  done
  [[ "${SKIP_BUILD:-false}" == true ]] || (cd "$repo_dir" && ./gradlew bootJar -q >/dev/null)
  mkdir -p "$repo_dir/build/verifier-cap"
  if [[ "${SKIP_BUILD:-false}" != true ]]; then
    jar=$(find "$repo_dir/build/libs" -maxdepth 1 -name '*.jar' ! -name '*plain*' | head -1); [[ -n "$jar" ]] || { echo 'no jar' >&2; exit 2; }
    cp "$jar" "$repo_dir/build/verifier-cap/app.jar"
  fi
  local i=0
  for port in "$PORT_A" "$PORT_B"; do
    i=$((i+1))
    SPRING_DATASOURCE_PASSWORD="$(mysql_pw)" LOADTEST_DB_PASSWORD="$(mysql_pw)" drun --name "$P-app$i" "${common[@]}" --cpus "$APP_CPUS" --memory "$APP_MEM" --memory-swap "$APP_MEM" -p "$port:8080" \
      -v "$repo_dir/build/verifier-cap/app.jar:/app.jar:ro" -v "$repo_dir/scripts/verifier-isolated/verifier-loadtest-config.yml:/cfg/config.yml:ro" \
      -e SPRING_DATASOURCE_URL="jdbc:mysql://$P-mysql:3306/$DB_NAME" -e SPRING_DATASOURCE_USERNAME=root -e SPRING_DATASOURCE_PASSWORD \
      -e VERIFIER_REDIS_MAIL_HOST="$P-redis-mail" -e VERIFIER_REDIS_MAIL_PORT=6379 -e VERIFIER_REDIS_COUPON_HOST="$P-redis-coupon" -e VERIFIER_REDIS_COUPON_PORT=6379 \
      -e VERIFIER_KAFKA_DUMMY_HOST=localhost "$JRE_IMAGE" \
      java -Xmx"$APP_XMX" -XX:+ExitOnOutOfMemoryError -jar /app.jar --server.port=8080 \
      --spring.profiles.active=verifier-loadtest,coupon-admission,coupon-admission-test,coupon-admission-batch,coupon-issuance,coupon-issuance-batch \
      --spring.config.additional-location=file:/cfg/config.yml --coupon.admission.diagnostics.enabled=false --spring.flyway.enabled=false >/dev/null
  done
  for port in "$PORT_A" "$PORT_B"; do
    for _ in $(seq 1 120); do curl -sf "http://127.0.0.1:$port/actuator/health" >/dev/null && break; sleep 1; done
    curl -sf "http://127.0.0.1:$port/actuator/health" >/dev/null || { echo "app on $port not healthy" >&2; docker logs --tail 20 "$P-app1" >&2; exit 1; }
  done
  echo up
}

owner_matches() {
  [[ -z "$OWNER" ]] || [[ "$(docker inspect --format '{{ index .Config.Labels "xyz.buzz.verifier.owner" }}' "$1" 2>/dev/null)" == "$OWNER" ]]
}
verify_all_owned() { # 존재하는 모든 대상의 라벨을 먼저 검증(하나라도 불일치면 아무것도 변경하지 않는다)
  local c lbl
  if docker network inspect "$NET" >/dev/null 2>&1; then
    [[ "$(docker network inspect --format '{{ index .Labels "xyz.buzz.verifier.managed" }}' "$NET")" == capped-harness ]] || { echo "network ownership mismatch" >&2; exit 2; }
    [[ -z "$OWNER" ]] || [[ "$(docker network inspect --format '{{ index .Labels "xyz.buzz.verifier.owner" }}' "$NET")" == "$OWNER" ]] || { echo "network owner mismatch" >&2; exit 2; }
  fi
  for c in $(owned_names); do
    docker inspect "$c" >/dev/null 2>&1 || continue
    lbl=$(docker inspect --format '{{ index .Config.Labels "xyz.buzz.verifier.managed" }}' "$c" 2>/dev/null || true)
    [[ "$lbl" == capped-harness ]] && owner_matches "$c" || { echo "ownership mismatch, not touching anything: $c" >&2; exit 2; }
  done
}

down() { # 정상 stop 만. 삭제(remove)는 별도 명시 명령.
  verify_all_owned
  local c
  for c in $(owned_names); do docker inspect "$c" >/dev/null 2>&1 || continue; docker stop "$c" >/dev/null || echo "stop failed: $c" >&2; done
}

remove() { # 명시 삭제: 소유 검증 + 모든 대상이 정지 상태일 때만, force 없음. 볼륨은 만들지 않았으므로 건드리지 않는다.
  verify_all_owned
  local c
  for c in $(owned_names); do
    docker inspect "$c" >/dev/null 2>&1 || continue
    [[ "$(docker inspect --format '{{.State.Running}}' "$c")" == false ]] || { echo "stop container before removal: $c" >&2; exit 2; }
  done
  for c in $(owned_names); do docker inspect "$c" >/dev/null 2>&1 || continue; docker rm "$c" >/dev/null || echo "remove failed: $c" >&2; done
  if docker network inspect "$NET" >/dev/null 2>&1; then
    [[ "$(docker network inspect --format '{{ index .Labels "xyz.buzz.verifier.managed" }}' "$NET")" == capped-harness ]] || { echo "network ownership mismatch" >&2; exit 2; }
    [[ -z "$OWNER" ]] || [[ "$(docker network inspect --format '{{ index .Labels "xyz.buzz.verifier.owner" }}' "$NET")" == "$OWNER" ]] || { echo "network owner mismatch" >&2; exit 2; }
    docker network rm "$NET" >/dev/null
  fi
}

create_event() { dbx "$DB_NAME" --batch --skip-column-names -e "INSERT INTO coupon_event (public_id,status,starts_at_utc,total_quantity,high_quantity,high_points,normal_points,settings_locked_at) VALUES ('$1','SCHEDULED',UTC_TIMESTAMP(6),$2,$3,10000,5000,UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();" | tail -1; }

k6_docker() {
  local out=$1; shift
  if [[ "${CAPTURE_K6_RUNTIME:-false}" == true ]]; then
    python3 "$repo_dir/scripts/verifier-isolated/run-k6-recorded.py" "$P-k6" "$OWNER" "$out/k6-cgroup.jsonl" "$@"
  else
    docker run --rm --name "$P-k6" "$@"
  fi
}

k6_run() { # $1=event $2=member_start $3=rate $4=duration $5=dup $6=out_dir $7=vus
  local ev=$1 ms=$2 rate=$3 dur=$4 dup=$5 out=$6 vus=${7:-6500}
  local envs=(EVENT_ID="$ev" MEMBER_ID_START="$ms" RATE="$rate" DURATION="$dur" DUPLICATE_RATE="$dup" POLL_BUDGET_MS=0
              PRE_ALLOCATED_VUS="$vus" MAX_VUS="${MAX_VUS:-$((vus+1500))}" HTTP_TIMEOUT="${HTTP_TIMEOUT:-10s}" GRACEFUL_STOP=30s)
  if [[ "$K6_MODE" == container ]]; then
    local e=(); for kv in "${envs[@]}"; do e+=(-e "$kv"); done
    k6_docker "$out" "${common[@]}" --cpus "$K6_CPUS" --memory "$K6_MEM" --memory-swap "$K6_MEM" \
      -v "$repo_dir/scripts/coupon-integrated-loadtest:/scripts:ro" -v "$out:/results" --user "$(id -u):$(id -g)" "${e[@]}" \
      -e TARGETS="http://$P-app1:8080,http://$P-app2:8080" "$K6_IMAGE" run --out json=/results/k6.json --summary-export /results/k6-summary.json \
      --console-output /results/k6-failures.log /scripts/integrated-admission-poll.js > "$out/k6-console.log" 2>&1 || true
  else
    env "${envs[@]}" TARGETS="http://127.0.0.1:$PORT_A,http://127.0.0.1:$PORT_B" GOMAXPROCS="$K6_GOMAXPROCS" GOMEMLIMIT="$K6_GOMEMLIMIT" \
      k6 run --out json="$out/k6.json" --summary-export "$out/k6-summary.json" --console-output "$out/k6-failures.log" \
      "$repo_dir/scripts/coupon-integrated-loadtest/integrated-admission-poll.js" > "$out/k6-console.log" 2>&1 || true
  fi
}

snap() { # 소유 컨테이너 상태 스냅샷: limits, cgroup cpu.stat, OOM 여부
  local f=$1; : > "$f"
  for c in "$P-app1" "$P-app2" "$P-mysql" "$P-redis-mail" "$P-redis-coupon"; do
    docker inspect "$c" --format '{{.Name}} nano_cpus={{.HostConfig.NanoCpus}} memory={{.HostConfig.Memory}} memswap={{.HostConfig.MemorySwap}} oom_score_adj={{.HostConfig.OomScoreAdj}} oom_killed={{.State.OOMKilled}} restarts={{.RestartCount}} status={{.State.Status}}' >> "$f" 2>&1
    echo "  cpu.stat: $(docker exec "$c" sh -c 'cat /sys/fs/cgroup/cpu.stat 2>/dev/null | tr "\n" " "' 2>&1)" >> "$f"
    echo "  mem.peak/events: $(docker exec "$c" sh -c 'cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events 2>/dev/null | tr "\n" " "' 2>&1)" >> "$f"
  done
}

run() {
  : "${RUN_LABEL:?RUN_LABEL}"; : "${RATE:?RATE}"
  local dur=${DURATION:-10s} dup=${DUPLICATE_RATE:-0.10} st=${STOCK_TOTAL:-1000} hi=${STOCK_HIGH:-500}
  local root=${REPORT_ROOT:-$HOME/.buzz/RESEARCH/verifier-runs/$RUN_LABEL-$(date +%m%d-%H%M)}
  local rd=$root/run; mkdir -p "$rd"
  "$repo_dir/scripts/verifier-isolated/capture-manifest.sh" "$root/manifest-before.txt" || true
  snap "$root/containers-before.txt"
  {
    echo "git_head=$(git -C "$repo_dir" rev-parse HEAD) dirty=$(git -C "$repo_dir" status --short | wc -l | tr -d ' ')"
    echo "rate=$RATE duration=$dur dup=$dup stock=$st high=$hi k6_mode=$K6_MODE"
    echo "caps: app cpus=$APP_CPUS mem=$APP_MEM xmx=$APP_XMX | db cpus=$DB_CPUS mem=$DB_MEM pool=$DB_POOL | redis cpus=$REDIS_CPUS mem=$REDIS_MEM | k6 container cpus=$K6_CPUS mem=$K6_MEM host gomaxprocs=$K6_GOMAXPROCS gomemlimit=$K6_GOMEMLIMIT"
    echo "jre_image=$JRE_IMAGE docker_image_ids: $(docker inspect "$P-app1" --format '{{.Image}}')"
  } > "$root/run-meta.txt"
  if [[ "${WARMUP:-true}" == true ]]; then
    local wev; wev=$(create_event "warmup-$(date -u +%s)" 100000 50000); mkdir -p "$root/warmup"
    k6_run "$wev" 600000000 "${WARMUP_RATE:-1000}" "${WARMUP_DURATION:-10s}" 0 "$root/warmup" 2000
    sleep 8
  fi
  local ev; ev=$(create_event "$RUN_LABEL-$(date -u +%s)" "$st" "$hi")
  echo "event_id=$ev member_start=700200000" >> "$root/run-meta.txt"
  for port in "$PORT_A" "$PORT_B"; do curl -sf "http://127.0.0.1:$port/actuator/prometheus" > "$rd/app$([[ $port == "$PORT_A" ]] && echo 1 || echo 2)-before.prom" || true; done
  snap "$root/containers-run-start.txt"
  python3 "$repo_dir/scripts/verifier-isolated/fast-sampler.py" "$rd" "$P-mysql" "$DB_NAME" "$ev" "$PORT_A" "$PORT_B" "${PROM_INTERVAL_S:-0.2}" & local sp=$!
  ( while :; do t=$(python3 -c 'import time;print(int(time.time()*1000))'); docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.PIDs}}' | sed "s/^/$t,/"; sleep 1; done ) > "$rd/docker-stats.csv" 2>/dev/null & local ds=$!
  python3 -c 'import time;print(int(time.time()*1000))' > "$rd/k6-started-host-ms.txt"
  k6_run "$ev" 700200000 "$RATE" "$dur" "$dup" "$rd" "${VUS:-6500}"
  # 수렴 대기(최대 300s): PENDING/CHECKING 0 이 3회 연속 같은 행 수
  local prev="" stable=0 deadline=$((SECONDS+300)) row
  while [[ $SECONDS -lt $deadline ]]; do
    row=$(dbx "$DB_NAME" --batch --skip-column-names -e "SELECT IFNULL(SUM(status IN ('PENDING','CHECKING')),0), COUNT(*) FROM coupon_application WHERE event_id=$ev" | tail -1) || row="DBERR"
    if [[ "$(docker inspect -f '{{.State.OOMKilled}}' "$P-mysql" 2>/dev/null)" == true ]]; then echo "MYSQL_OOMKILLED" >> "$rd/quiescence-poll.tsv"; break; fi
    echo "$(date -u +%FT%TZ) $row" >> "$rd/quiescence-poll.tsv"
    if [[ "${row%%$'\t'*}" == 0 && "$row" == "$prev" ]]; then stable=$((stable+1)); [[ $stable -ge 3 ]] && break; else stable=0; fi
    prev=$row; sleep 2
  done
  kill "$sp" "$ds" 2>/dev/null || true; wait "$sp" "$ds" 2>/dev/null || true
  for port in "$PORT_A" "$PORT_B"; do curl -sf "http://127.0.0.1:$port/actuator/prometheus" > "$rd/app$([[ $port == "$PORT_A" ]] && echo 1 || echo 2)-after.prom" || true; done
  snap "$root/containers-after.txt"
  "$repo_dir/scripts/verifier-isolated/capture-manifest.sh" "$root/manifest-after.txt" || true
  local q="event_id=$ev"
  dbx "$DB_NAME" --batch --skip-column-names -e "SELECT id,status,next_acceptance_sequence,issued_quantity,total_quantity,high_quantity FROM coupon_event WHERE id=$ev" > "$rd/event-final.tsv" || true
  dbx "$DB_NAME" --batch --skip-column-names -e "SELECT status,COUNT(*) FROM coupon_application WHERE $q GROUP BY status" > "$rd/application-status-counts.tsv" || true
  dbx "$DB_NAME" --batch --skip-column-names -e "SELECT COUNT(*) FROM coupon_award WHERE $q" > "$rd/award-total.txt" || true
  dbx "$DB_NAME" --batch --skip-column-names -e "SELECT COUNT(*) FROM (SELECT member_id FROM coupon_award WHERE $q GROUP BY member_id HAVING COUNT(*)>1) d" > "$rd/duplicate-award-by-member.txt" || true
  dbx "$DB_NAME" --batch --skip-column-names -e "SELECT COUNT(*) FROM (SELECT application_id FROM coupon_award GROUP BY application_id HAVING COUNT(*)>1) d" > "$rd/duplicate-award-by-application.txt" || true
  dbx "$DB_NAME" --batch --skip-column-names -e "SELECT COUNT(*) FROM coupon_application WHERE $q AND status IN ('PENDING','CHECKING')" > "$rd/unresolved-after-quiescence.txt" || true
  dbx "$DB_NAME" --batch --skip-column-names -e "SELECT ca.acceptance_sequence, cw.tier FROM coupon_application ca JOIN coupon_award cw ON cw.application_id=ca.id WHERE ca.$q AND ((ca.acceptance_sequence<=(SELECT high_quantity FROM coupon_event WHERE id=$ev) AND cw.tier<>'HIGH') OR (ca.acceptance_sequence>(SELECT high_quantity FROM coupon_event WHERE id=$ev) AND cw.tier<>'NORMAL'))" > "$rd/tier-mismatch.tsv" || true
  docker exec "$P-mysql" sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysqldump -uroot --no-tablespaces $DB_NAME 2>/dev/null" | gzip > "$root/db-dump.sql.gz" || true
  python3 "$repo_dir/scripts/verifier-isolated/analyze-separated-v2.py" "$rd" "$root/analysis" "$P-mysql" "$DB_NAME" "$ev" 700200000 > /dev/null 2>"$root/analysis-stderr.txt" || echo "analysis failed" >> "$root/analysis-stderr.txt"
  echo "$root"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then   # source 되면 함수만 제공(run-capped-stock.sh 가 재사용)
  case "${1:-}" in up) up ;; run) run ;; down) down ;; remove) remove ;; *) echo "usage: $0 up|run|down|remove" >&2; exit 1 ;; esac
fi
