#!/usr/bin/env bash
set -euo pipefail

# 묶음 발급 경로(CouponEventIssuanceBatchTransactionService, coupon.event-issuance.batch.enabled=true)
# 검증이다. 단건 발급 검증과 같은 패턴으로 독립 Spring Boot 프로세스 2개 + 전용 MySQL DB에서
# 실행하고, 두 앱 모두에서 발급 워커를 동시에 돌린다. 접수는 기존 단건 경로만 쓴다. 성능 목표는
# 이 스크립트의 범위가 아니다 - 정합성만 확인한다: 묶음 중간 실패의 전체 롤백, 두 앱 동시 드레인에서
# 중복·초과 발급 없음, 순서 보존.

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
mysql_container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_ISSUANCE_TEST_DB:-givemeticon_coupon_issuance_batch_validation}
port_a=${COUPON_ISSUANCE_PORT_A:-18110}
port_b=${COUPON_ISSUANCE_PORT_B:-18111}
log_dir="$repo_dir/build/coupon-issuance-batch-validation"
mkdir -p "$log_dir"

if ! docker ps --format '{{.Names}}' | grep -qx "$mysql_container"; then
  echo "MySQL container '$mysql_container' is not running." >&2
  exit 1
fi

mysql_password=$(docker exec "$mysql_container" printenv MYSQL_ROOT_PASSWORD)
app_a_pid=''
app_b_pid=''

cleanup() {
  [[ -n "$app_a_pid" ]] && kill -9 "$app_a_pid" 2>/dev/null || true
  [[ -n "$app_b_pid" ]] && kill -9 "$app_b_pid" 2>/dev/null || true
}
trap cleanup EXIT

mysql_exec() {
  docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" --batch --skip-column-names -e "$1"
}

# -e는 문자열 안의 세미콜론마다 문장을 쪼갠다 - 트리거 본문처럼 내부에 세미콜론이 있는 다문장
# SQL은 DELIMITER로 감싸 표준입력으로 넘겨야 한다.
mysql_exec_delimited() {
  printf 'DELIMITER $$\n%s$$\nDELIMITER ;\n' "$1" | docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database"
}

docker exec "$mysql_container" mysql -uroot -p"$mysql_password" -e "DROP DATABASE IF EXISTS \`$database\`; CREATE DATABASE \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260920__add_coupon_event_admission_ledger.sql"
docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260923__add_coupon_award.sql"

./gradlew bootJar >/dev/null
jar_path=$(find "$repo_dir/build/libs" -maxdepth 1 -name '*.jar' ! -name '*plain*' | head -1)
[[ -n "$jar_path" ]] || { echo 'boot jar not found' >&2; exit 2; }

start_app() {
  local port=$1 logfile=$2
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/$database" \
  SPRING_DATASOURCE_USERNAME=root \
  SPRING_DATASOURCE_PASSWORD="$mysql_password" \
  java -jar "$jar_path" \
    --server.port="$port" \
    --spring.profiles.active=local,coupon-admission,coupon-admission-test,coupon-issuance,coupon-issuance-batch \
    --coupon.event-issuance.batch.size="${BATCH_SIZE:-5}" \
    --spring.flyway.enabled=false >"$logfile" 2>&1 &
  echo $!
}
wait_for_app() {
  local port=$1 logfile=$2
  for _ in $(seq 1 90); do
    curl --silent --fail "http://localhost:$port/actuator/health" >/dev/null && return 0
    sleep 1
  done
  echo "Application on port $port did not start. See $logfile" >&2
  return 1
}

app_a_pid=$(start_app "$port_a" "$log_dir/app-$port_a.log")
app_b_pid=$(start_app "$port_b" "$log_dir/app-$port_b.log")
wait_for_app "$port_a" "$log_dir/app-$port_a.log"
wait_for_app "$port_b" "$log_dir/app-$port_b.log"
curl --silent "http://localhost:$port_a/actuator/beans" | grep -q 'couponEventIssuanceBatchTransactionService' \
  && echo "confirmed: batch issuance service bean registered on app A" \
  || { echo "batch issuance service bean not found on app A" >&2; exit 1; }
curl --silent "http://localhost:$port_a/actuator/beans" | grep -q 'couponEventIssuanceTransactionService' \
  && { echo "unexpected: single-row issuance service is also registered alongside batch" >&2; exit 1; }
echo "confirmed: single-row issuance service is not registered while batch is active"

create_event() {
  local public_id=$1 total_quantity=$2 high_quantity=$3
  mysql_exec "INSERT INTO coupon_event (public_id, status, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at) VALUES ('$public_id', 'SCHEDULED', UTC_TIMESTAMP(6), $total_quantity, $high_quantity, 10000, 5000, UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();" | tail -1
}

post() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications" >/dev/null
}

admit_all() {
  local event_id=$1 first=$2 last=$3
  local pids=()
  for member in $(seq "$first" "$last"); do
    local port=$port_a
    [[ $((member % 2)) -eq 0 ]] && port=$port_b
    ( post "$port" "$event_id" "$member" ) &
    pids+=($!)
  done
  wait "${pids[@]}"
}

fail_count=0
check() {
  local description=$1 result=$2
  if [[ "$result" == "0" ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description" >&2
    fail_count=$((fail_count + 1))
  fi
}

wait_for_no_pending() {
  local event_id=$1 timeout_secs=${2:-60}
  local deadline=$((SECONDS + timeout_secs))
  while [[ $SECONDS -lt $deadline ]]; do
    local pending
    pending=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_id AND status='PENDING'")
    [[ "$pending" == "0" ]] && return 0
    sleep 0.3
  done
  return 1
}

# --- 1. 정상 묶음 발급: 150명(총수량 100/고액 50) -> 정확히 100장(고액 50/일반 50), 나머지 50 SOLD_OUT.
#         Com_insert/Com_commit 델타로 실제 다건 처리(묶음)를 확인한다.
event1=$(create_event "issuance-batch-150" 100 50)
before_com=$(mysql_exec "SHOW GLOBAL STATUS LIKE 'Com_insert'" | awk '{print $2}')
before_commit=$(mysql_exec "SHOW GLOBAL STATUS LIKE 'Com_commit'" | awk '{print $2}')
admit_all "$event1" 1 150
check "1 all 150 applications resolve (no PENDING left) within 60s" \
  "$(wait_for_no_pending "$event1" 60 && echo 0 || echo 1)"
after_com=$(mysql_exec "SHOW GLOBAL STATUS LIKE 'Com_insert'" | awk '{print $2}')
after_commit=$(mysql_exec "SHOW GLOBAL STATUS LIKE 'Com_commit'" | awk '{print $2}')
com_insert_delta=$((after_com - before_com))
com_commit_delta=$((after_commit - before_commit))
echo "info: Com_insert delta=$com_insert_delta Com_commit delta=$com_commit_delta for 150 applications (batch-size=${BATCH_SIZE:-5})"
# 참고용 수치로만 남긴다: Com_commit은 이 DB 세션 전체의 커밋을 센다 - 접수(단건, 요청당 1커밋)와
# 발급(묶음) 커밋이 섞여 있어 이 값만으로 "발급이 몇 번 커밋했는지"를 분리하지 못한다. 다건 처리
# 증거는 아래 각 신청의 실제 등급·순번·소진 판정이 정확한지(정합성)로 확인한다 - 커밋 횟수
# 자체를 pass/fail 기준으로 쓰지 않는다.

app_status_counts=$(mysql_exec "SELECT SUM(status='ISSUED'), SUM(status='SOLD_OUT'), SUM(status='PENDING'), COUNT(*) FROM coupon_application WHERE event_id=$event1")
check "1 application status split is 100 ISSUED / 50 SOLD_OUT / 0 PENDING / 150 total" \
  "$([[ "$app_status_counts" == $'100\t50\t0\t150' ]] && echo 0 || echo 1)"
join_check=$(mysql_exec "SELECT
    SUM(a.acceptance_sequence BETWEEN 1 AND 50 AND a.status='ISSUED' AND w.tier='HIGH' AND w.points=10000),
    SUM(a.acceptance_sequence BETWEEN 51 AND 100 AND a.status='ISSUED' AND w.tier='NORMAL' AND w.points=5000),
    SUM(a.acceptance_sequence BETWEEN 101 AND 150 AND a.status='SOLD_OUT' AND w.id IS NULL)
  FROM coupon_application a LEFT JOIN coupon_award w ON w.application_id=a.id
  WHERE a.event_id=$event1")
check "1 per-member sequence/tier/points join matches exactly (50/50/50)" \
  "$([[ "$join_check" == $'50\t50\t50' ]] && echo 0 || echo 1)"
check "1 no duplicate coupon_award rows (by application_id or by event+member)" \
  "$([[ "$(mysql_exec "SELECT (SELECT COUNT(*) FROM (SELECT application_id FROM coupon_award GROUP BY application_id HAVING COUNT(*)>1) d) + (SELECT COUNT(*) FROM (SELECT event_id, member_id FROM coupon_award GROUP BY event_id, member_id HAVING COUNT(*)>1) d2)")" == "0" ]] && echo 0 || echo 1)"
check "1 issued_quantity is exactly total_quantity (100), no over-issuance" \
  "$([[ "$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event1")" == "100" ]] && echo 0 || echo 1)"

# --- 2. 묶음 중간 강제 실패 -> 그 배치 전원이 PENDING으로 남고(건너뛰지 않음), 이미 쓴 award·수량도
#         함께 롤백된다. 배치 크기(5)보다 적은 수(3번째)에서 실패시켜 "배치 일부가 이미 처리된 뒤"
#         실패하는 경우까지 확인한다.
event2=$(create_event "issuance-batch-rollback" 20 10)
mysql_exec_delimited "CREATE TRIGGER coupon_award_fail_on_third BEFORE INSERT ON coupon_award FOR EACH ROW BEGIN IF (SELECT COUNT(*) FROM coupon_award WHERE event_id=$event2) >= 2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced mid-batch issuance failure'; END IF; END"
admit_all "$event2" 301 305
sleep 2
pending_count=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event2 AND status='PENDING'")
check "2 during forced mid-batch failure, all 5 applications in that batch stay PENDING" \
  "$([[ "$pending_count" == "5" ]] && echo 0 || echo 1)"
check "2 during forced mid-batch failure, the 2 awards already inserted in that transaction are rolled back (0 rows)" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award WHERE event_id=$event2")" == "0" ]] && echo 0 || echo 1)"
check "2 during forced mid-batch failure, issued_quantity stays 0 (rolled back)" \
  "$([[ "$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event2")" == "0" ]] && echo 0 || echo 1)"
mysql_exec "DROP TRIGGER coupon_award_fail_on_third"
check "2 after removing the trigger, all 5 resolve without skipping earlier sequences" \
  "$(wait_for_no_pending "$event2" 30 && echo 0 || echo 1)"
final2=$(mysql_exec "SELECT SUM(status='ISSUED'), SUM(status='SOLD_OUT') FROM coupon_application WHERE event_id=$event2")
check "2 all 5 sequence-ordered applications end up ISSUED (capacity 20 >= 5), none skipped" \
  "$([[ "$final2" == $'5\t0' ]] && echo 0 || echo 1)"

# --- 3. 두 앱이 동시에 같은 행사를 묶음으로 드레인 -> 중복·초과 발급 없음, 접수번호 연속·순서 보존.
event3=$(create_event "issuance-batch-concurrent" 100 50)
admit_all "$event3" 401 800
check "3 all 400 applications resolve within 90s (two apps draining concurrently)" \
  "$(wait_for_no_pending "$event3" 90 && echo 0 || echo 1)"
app_status_counts3=$(mysql_exec "SELECT SUM(status='ISSUED'), SUM(status='SOLD_OUT'), SUM(status='PENDING'), COUNT(*) FROM coupon_application WHERE event_id=$event3")
check "3 application status split is 100 ISSUED / 300 SOLD_OUT / 0 PENDING / 400 total" \
  "$([[ "$app_status_counts3" == $'100\t300\t0\t400' ]] && echo 0 || echo 1)"
check "3 no duplicate coupon_award rows across the two concurrently-draining apps" \
  "$([[ "$(mysql_exec "SELECT (SELECT COUNT(*) FROM (SELECT application_id FROM coupon_award GROUP BY application_id HAVING COUNT(*)>1) d) + (SELECT COUNT(*) FROM (SELECT event_id, member_id FROM coupon_award WHERE event_id=$event3 GROUP BY event_id, member_id HAVING COUNT(*)>1) d2)")" == "0" ]] && echo 0 || echo 1)"
check "3 issued_quantity exactly equals total_quantity (100), no over-issuance from concurrent batches" \
  "$([[ "$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event3")" == "100" ]] && echo 0 || echo 1)"
non_contig=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event3 AND acceptance_sequence <> (SELECT COUNT(*) FROM coupon_application a2 WHERE a2.event_id=$event3 AND a2.acceptance_sequence <= coupon_application.acceptance_sequence)")
check "3 acceptance_sequence is contiguous 1..400 (order preserved across concurrent apps)" \
  "$([[ "$non_contig" == "0" ]] && echo 0 || echo 1)"
order_check=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event3 AND acceptance_sequence<=100 AND status<>'ISSUED'")
check "3 every application with sequence<=100 (and only those) ended up ISSUED" \
  "$([[ "$order_check" == "0" ]] && echo 0 || echo 1)"

echo
if [[ "$fail_count" -eq 0 ]]; then
  echo "ALL PASS"
else
  echo "$fail_count FAILED" >&2
  exit 1
fi
