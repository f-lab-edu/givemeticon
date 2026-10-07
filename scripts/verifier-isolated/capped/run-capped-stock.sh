#!/usr/bin/env bash
set -euo pipefail
# stock(Redisson 분산락 + MySQL) 동기 발급 경로의 자원 제한 baseline runner. run-capped-v1.sh 와 동일한 격리·cap·가드를 쓴다.
# 앱은 mysql-loadtest + redis-lock-loadtest 프로필이며, JDBC/Redis/Kafka 고정값은 환경변수·명령행 인자로 격리 인스턴스에 덮어쓴다.
# 사용: capped/run-capped-stock.sh up|run|down  (환경변수는 run-capped-v1.sh 와 동일 + MODE=sync|accept, STOCK_TOTAL, USER_ID_START)
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=run-capped-v1.sh
source "$here/run-capped-v1.sh"
DB_NAME=${DB_NAME:-givemeticon_loadtest_cap}
HIKARI_MAX=${LOADTEST_HIKARI_MAX:-20}

up_stock() {
  "$here/memory-guard.sh" || exit 3
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
  dbx "$DB_NAME" < "$repo_dir/scripts/loadtest/mysql-coupon-schema.sql"
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
      -e LOADTEST_DB_URL="jdbc:mysql://$P-mysql:3306/$DB_NAME" -e LOADTEST_DB_USER=root -e LOADTEST_DB_PASSWORD \
      -e LOADTEST_HIKARI_MAX="$HIKARI_MAX" -e COUPON_ISSUE_WORKER_MODE=off "$JRE_IMAGE" \
      java -Xmx"$APP_XMX" -XX:+ExitOnOutOfMemoryError -jar /app.jar --server.port=8080 \
      --spring.profiles.active=verifier-loadtest,mysql-loadtest,redis-lock-loadtest \
      --spring.config.additional-location=file:/cfg/config.yml \
      --spring.data.redis.mail.host="$P-redis-mail" --spring.data.redis.mail.port=6379 \
      --spring.data.redis.coupon.host="$P-redis-coupon" --spring.data.redis.coupon.port=6379 \
      --bootstrap.server=localhost:19092 --spring.flyway.enabled=false >/dev/null
  done
  for port in "$PORT_A" "$PORT_B"; do
    for _ in $(seq 1 120); do curl -sf "http://127.0.0.1:$port/actuator/health" >/dev/null && break; sleep 1; done
    curl -sf "http://127.0.0.1:$port/actuator/health" >/dev/null || { echo "app on $port not healthy" >&2; exit 1; }
  done
  echo up
}

k6_stock() { # $1=stock_id $2=user_start $3=rate $4=duration $5=out_dir
  local envs=(STOCK_ID="$1" USER_ID_START="$2" RATE="$3" DURATION="$4" MODE="${MODE:-sync}" DUPLICATE_RATE="${DUPLICATE_RATE:-0.10}" RUN_ID="$RUN_LABEL" HTTP_TIMEOUT="${HTTP_TIMEOUT:-10s}"
              PRE_ALLOCATED_VUS="${VUS:-2000}" MAX_VUS="${MAX_VUS:-8000}")
  local script="$repo_dir/scripts/verifier-isolated/stock-arrival-record.js"
  if [[ "$K6_MODE" == container ]]; then
    local e=(); for kv in "${envs[@]}"; do e+=(-e "$kv"); done
    docker run --rm --name "$P-k6" "${common[@]}" --cpus "$K6_CPUS" --memory "$K6_MEM" --memory-swap "$K6_MEM" \
      -v "$repo_dir/scripts/verifier-isolated:/scripts:ro" -v "$5:/results" --user "$(id -u):$(id -g)" "${e[@]}" -e TARGETS="http://$P-app1:8080,http://$P-app2:8080" \
      "$K6_IMAGE" run --out json=/results/k6.json --summary-export /results/k6-summary.json --console-output /results/k6-failures.log \
      /scripts/stock-arrival-record.js > "$5/k6-console.log" 2>&1 || true
  else
    env "${envs[@]}" TARGETS="http://127.0.0.1:$PORT_A,http://127.0.0.1:$PORT_B" GOMAXPROCS="$K6_GOMAXPROCS" GOMEMLIMIT="$K6_GOMEMLIMIT" \
      k6 run --out json="$5/k6.json" --summary-export "$5/k6-summary.json" --console-output "$5/k6-failures.log" "$script" > "$5/k6-console.log" 2>&1 || true
  fi
}

run_stock() {
  : "${RUN_LABEL:?RUN_LABEL}"; : "${RATE:?RATE}"
  local dur=${DURATION:-10s} total=${STOCK_TOTAL:-1000} ustart=${USER_ID_START:-900000000}
  local root=${REPORT_ROOT:-$HOME/.buzz/RESEARCH/verifier-runs/$RUN_LABEL-$(date +%m%d-%H%M)}
  local rd=$root/run; mkdir -p "$rd"
  "$repo_dir/scripts/verifier-isolated/capture-manifest.sh" "$root/manifest-before.txt" || true
  snap "$root/containers-before.txt"
  { echo "path=stock(redisson+mysql) mode=${MODE:-sync} git_head=$(git -C "$repo_dir" rev-parse HEAD) dirty=$(git -C "$repo_dir" status --short | wc -l | tr -d ' ')"
    echo "rate=$RATE duration=$dur stock_total=$total hikari_max=$HIKARI_MAX k6_mode=$K6_MODE"
    echo "caps: app cpus=$APP_CPUS mem=$APP_MEM xmx=$APP_XMX | db cpus=$DB_CPUS mem=$DB_MEM pool=$DB_POOL | redis cpus=$REDIS_CPUS mem=$REDIS_MEM"
  } > "$root/run-meta.txt"
  local sid; sid=$(dbx "$DB_NAME" --batch --skip-column-names -e "INSERT INTO coupon_stock(total, remain) VALUES ($total, $total); SELECT LAST_INSERT_ID();" | tail -1)
  echo "stock_id=$sid user_id_start=$ustart" >> "$root/run-meta.txt"
  for port in "$PORT_A" "$PORT_B"; do curl -sf "http://127.0.0.1:$port/actuator/prometheus" > "$rd/app$([[ $port == "$PORT_A" ]] && echo 1 || echo 2)-before.prom" || true; done
  snap "$root/containers-run-start.txt"
  ( while :; do t=$(python3 -c 'import time;print(int(time.time()*1000))'); docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.PIDs}}' | sed "s/^/$t,/"; sleep 1; done ) > "$rd/docker-stats.csv" 2>/dev/null & local ds=$!
  k6_stock "$sid" "$ustart" "$RATE" "$dur" "$rd"
  # 수렴: accept 모드는 워커 처리 대기(PENDING 0, 행 수 안정), sync 는 즉시 안정
  local prev="" stable=0 deadline=$((SECONDS+${DRAIN_MAX_S:-180})) row
  while [[ $SECONDS -lt $deadline ]]; do
    row=$(dbx "$DB_NAME" --batch --skip-column-names -e "SELECT (SELECT COUNT(*) FROM coupon WHERE stock_id=$sid), (SELECT COUNT(*) FROM coupon_issue_request WHERE stock_id=$sid AND status='PENDING')" | tail -1) || row="DBERR"
    echo "$(date -u +%FT%TZ) $row" >> "$rd/quiescence-poll.tsv"
    if [[ "$(docker inspect -f '{{.State.OOMKilled}}' "$P-mysql" 2>/dev/null)" == true ]]; then echo "MYSQL_OOMKILLED" >> "$rd/quiescence-poll.tsv"; break; fi
    if [[ "$row" == "$prev" && "${row##*$'\t'}" == 0 ]]; then stable=$((stable+1)); [[ $stable -ge 3 ]] && break; else stable=0; fi
    prev=$row; sleep 2
  done
  kill "$ds" 2>/dev/null || true; wait "$ds" 2>/dev/null || true
  for port in "$PORT_A" "$PORT_B"; do curl -sf "http://127.0.0.1:$port/actuator/prometheus" > "$rd/app$([[ $port == "$PORT_A" ]] && echo 1 || echo 2)-after.prom" || true; done
  snap "$root/containers-after.txt"
  "$repo_dir/scripts/verifier-isolated/capture-manifest.sh" "$root/manifest-after.txt" || true
  { printf 'SET @stock_id=%s;\nSET @stock_total=%s;\n' "$sid" "$total"; sed -n '2,$p' "$repo_dir/scripts/loadtest/verify-event.sql"; } | dbx "$DB_NAME" --batch --skip-column-names > "$rd/consistency.txt" || true
  docker exec "$P-mysql" sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysqldump -uroot --no-tablespaces $DB_NAME 2>/dev/null" | gzip > "$root/db-dump.sql.gz" || true
  # 락 지표 근거 수집: 앱 로그 원본 + 락 어노테이션 사이트/설정(소스 감사, 실행 시점 SHA 기준) + 앱별 before/after prom 분석
  for i in 1 2; do docker logs "$P-app$i" > "$rd/app$i.log" 2>&1 || true; done
  git -C "$repo_dir" grep -n -A3 '@DistributedLock' -- src/main/java > "$root/lock-annotation-sites.txt" 2>/dev/null || true
  echo "lock defaults: waitTime=5s leaseTime=3s unless site overrides (see lock-annotation-sites.txt); worker_mode=${COUPON_ISSUE_WORKER_MODE_RUN:-off}(apps started with COUPON_ISSUE_WORKER_MODE=off)" >> "$root/run-meta.txt"
  python3 "$repo_dir/scripts/verifier-isolated/analyze-lock-metrics.py" "$rd" "$root/analysis-lock" > /dev/null 2>"$root/analysis-lock-stderr.txt" || echo "lock analysis failed" >> "$root/analysis-lock-stderr.txt"
  python3 "$repo_dir/scripts/verifier-isolated/analyze-stock.py" "$rd" "$root/analysis" "$P-mysql" "$DB_NAME" "$sid" "$ustart" "$total" --dup-rate="${DUPLICATE_RATE:-0.10}" > /dev/null 2>"$root/analysis-stderr.txt" || echo "analysis failed" >> "$root/analysis-stderr.txt"
  echo "$root"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in up) up_stock ;; run) run_stock ;; down) down ;; remove) remove ;; *) echo "usage: $0 up|run|down|remove" >&2; exit 1 ;; esac
fi
