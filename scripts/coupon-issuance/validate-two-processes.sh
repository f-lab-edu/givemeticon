#!/usr/bin/env bash
set -euo pipefail

# 접수 순서에 따른 쿠폰 발급 검증이다. 실제 MySQL 하나에 독립 Spring Boot 프로세스 두 개를 연결해
# 두 앱 모두에서 발급 워커(CouponEventIssuanceWorker)를 동시에 돌린다. 접수는 기존 단건 경로만
# 쓴다(coupon-admission-batch 프로필은 켜지 않는다 - 묶음 접수는 실험 경로로 보존). 기존
# givemeticon DB와 다른 검증 DB는 건드리지 않고 전용 DB만 생성/삭제한다.
#
# 성능 목표(p95 2초 등)는 이 스크립트의 범위가 아니다 - 정합성만 확인한다.

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
mysql_container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_ISSUANCE_TEST_DB:-givemeticon_coupon_issuance_validation}
port_a=${COUPON_ISSUANCE_PORT_A:-18100}
port_b=${COUPON_ISSUANCE_PORT_B:-18101}
log_dir="$repo_dir/build/coupon-issuance-validation"
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

docker exec "$mysql_container" mysql -uroot -p"$mysql_password" -e "DROP DATABASE IF EXISTS \`$database\`; CREATE DATABASE \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260920__add_coupon_event_admission_ledger.sql"
docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260923__add_coupon_award.sql"

start_app() {
  local port=$1 logfile=$2
  (
    cd "$repo_dir"
    SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/$database" \
    SPRING_DATASOURCE_USERNAME=root \
    SPRING_DATASOURCE_PASSWORD="$mysql_password" \
    ./gradlew bootRun --args="--server.port=$port --spring.profiles.active=local,coupon-admission,coupon-admission-test,coupon-issuance --spring.flyway.enabled=false"
  ) >"$logfile" 2>&1 &
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
curl --silent "http://localhost:$port_a/actuator/beans" | grep -q 'couponEventIssuanceWorker' \
  && echo "confirmed: new issuance worker bean registered on app A" \
  || { echo "issuance worker bean not found on app A" >&2; exit 1; }
curl --silent "http://localhost:$port_a/actuator/beans" | grep -qE 'couponIssueAsyncWorker|couponBatchIssueWorker' \
  && { echo "unexpected: an old experimental issue worker bean is also registered" >&2; exit 1; }
echo "confirmed: old experimental issue workers are not registered alongside the new issuance worker"

create_event() {
  local public_id=$1 starts_at=$2 total_quantity=$3 high_quantity=$4 high_points=$5 normal_points=$6
  mysql_exec "INSERT INTO coupon_event (public_id, status, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at) VALUES ('$public_id', 'SCHEDULED', $starts_at, $total_quantity, $high_quantity, $high_points, $normal_points, UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();"
}

post() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications"
}
post_code() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications"
}
get_me() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications/me"
}

request_id() { jq -r '.data.requestId'; }
sequence() { jq -r '.data.acceptanceSequence'; }
status_field() { jq -r '.data.status'; }
tier_field() { jq -r '.data.couponTier'; }
points_field() { jq -r '.data.couponPoints'; }

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
require_field() {
  local description=$1 value=$2
  if [[ -z "$value" || "$value" == "null" ]]; then
    check "$description" 1
    return 1
  fi
  return 0
}

admit_all() {
  # 짝/홀 회원 ID로 두 앱에 번갈아 접수한다(단건 접수 경로만 사용).
  local event_id=$1 first=$2 last=$3
  local pids=()
  for member in $(seq "$first" "$last"); do
    local port=$port_a
    [[ $((member % 2)) -eq 0 ]] && port=$port_b
    ( post "$port" "$event_id" "$member" >/dev/null ) &
    pids+=($!)
  done
  wait "${pids[@]}"
}

wait_for_drained() {
  # 행사가 CLOSED이고 남은 PENDING이 없을 때까지 기다린다. 발급 대상이 준비 수량보다 적어
  # CLOSED가 되지 않는 행사(시나리오 3)는 wait_for_no_pending을 대신 쓴다.
  local event_id=$1 timeout_secs=${2:-60}
  local deadline=$((SECONDS + timeout_secs))
  while [[ $SECONDS -lt $deadline ]]; do
    local status pending
    status=$(mysql_exec "SELECT status FROM coupon_event WHERE id=$event_id")
    pending=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_id AND status='PENDING'")
    if [[ "$status" == "CLOSED" && "$pending" == "0" ]]; then
      return 0
    fi
    sleep 0.3
  done
  return 1
}

wait_for_no_pending() {
  local event_id=$1 timeout_secs=${2:-30}
  local deadline=$((SECONDS + timeout_secs))
  while [[ $SECONDS -lt $deadline ]]; do
    local pending
    pending=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_id AND status='PENDING'")
    [[ "$pending" == "0" ]] && return 0
    sleep 0.3
  done
  return 1
}

# 1. 150명 접수 -> 정확히 100장 발급(고액 50/일반 50), 나머지 50명 SOLD_OUT. 순서·금액 대조.
event1=$(create_event "issuance-150" "UTC_TIMESTAMP(6)" 100 50 10000 5000 | tail -1)
admit_all "$event1" 1 150
check "1 event drains to CLOSED within 60s" "$(wait_for_drained "$event1" 60 && echo 0 || echo 1)"

event1_stats=$(mysql_exec "SELECT status, COUNT(*) FROM coupon_event WHERE id=$event1")
check "1 event issued_quantity is exactly 100" \
  "$([[ "$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event1")" == "100" ]] && echo 0 || echo 1)"
app_status_counts=$(mysql_exec "SELECT SUM(status='ISSUED'), SUM(status='SOLD_OUT'), SUM(status='PENDING'), COUNT(*) FROM coupon_application WHERE event_id=$event1")
check "1 application status split is 100 ISSUED / 50 SOLD_OUT / 0 PENDING / 150 total" \
  "$([[ "$app_status_counts" == $'100\t50\t0\t150' ]] && echo 0 || echo 1)"
award_stats=$(mysql_exec "SELECT SUM(tier='HIGH'), SUM(tier='NORMAL'), COUNT(*) FROM coupon_award WHERE event_id=$event1")
check "1 coupon_award split is 50 HIGH / 50 NORMAL / 100 total" \
  "$([[ "$award_stats" == $'50\t50\t100' ]] && echo 0 || echo 1)"

# 회원별 접수 순번-등급-포인트를 직접 대조한다: 1~50=HIGH/10000, 51~100=NORMAL/5000, 101~150=SOLD_OUT/쿠폰없음.
join_check=$(mysql_exec "SELECT
    SUM(a.acceptance_sequence BETWEEN 1 AND 50 AND a.status='ISSUED' AND w.tier='HIGH' AND w.points=10000),
    SUM(a.acceptance_sequence BETWEEN 51 AND 100 AND a.status='ISSUED' AND w.tier='NORMAL' AND w.points=5000),
    SUM(a.acceptance_sequence BETWEEN 101 AND 150 AND a.status='SOLD_OUT' AND w.id IS NULL)
  FROM coupon_application a LEFT JOIN coupon_award w ON w.application_id=a.id
  WHERE a.event_id=$event1")
check "1 per-member sequence/tier/points join matches exactly (50/50/50)" \
  "$([[ "$join_check" == $'50\t50\t50' ]] && echo 0 || echo 1)"

# acceptance_sequence는 커밋 순서로 정해지며 member_id 순서와 무관하다(150명을 동시에 접수했으므로
# 어떤 member_id가 어떤 순번을 받을지는 결정돼 있지 않다) - 이후 시나리오에서 쓸 ISSUED/SOLD_OUT
# 회원은 하드코딩하지 않고 실제 원장에서 조회한다.
issued_member=$(mysql_exec "SELECT member_id FROM coupon_application WHERE event_id=$event1 AND status='ISSUED' LIMIT 1")
soldout_member=$(mysql_exec "SELECT member_id FROM coupon_application WHERE event_id=$event1 AND status='SOLD_OUT' LIMIT 1")

# 2. 반복 처리·재신청에도 같은 쿠폰을 반환하고 재고를 다시 차감하지 않는다.
before_award_count=$(mysql_exec "SELECT COUNT(*) FROM coupon_award WHERE event_id=$event1")
before_issued_quantity=$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event1")
first_lookup=$(get_me "$port_a" "$event1" "$issued_member")
retry_admission=$(post "$port_b" "$event1" "$issued_member")
second_lookup=$(get_me "$port_b" "$event1" "$issued_member")
first_id=$(printf '%s' "$first_lookup" | request_id)
retry_id=$(printf '%s' "$retry_admission" | request_id)
second_id=$(printf '%s' "$second_lookup" | request_id)
if require_field "2 first lookup has a non-null requestId" "$first_id" \
  && require_field "2 retry admission has a non-null requestId" "$retry_id" \
  && require_field "2 second lookup has a non-null requestId" "$second_id"; then
  check "2 lookup, retry-admission and second lookup all return the same requestId" \
    "$([[ "$first_id" == "$retry_id" && "$retry_id" == "$second_id" ]] && echo 0 || echo 1)"
fi
first_tier=$(printf '%s' "$first_lookup" | tier_field)
first_points=$(printf '%s' "$first_lookup" | points_field)
if require_field "2 first lookup has a non-null couponTier (member is actually ISSUED)" "$first_tier" \
  && require_field "2 first lookup has a non-null couponPoints" "$first_points"; then
  check "2 repeated lookups return the same tier/points" \
    "$([[ "$first_tier" == "$(printf '%s' "$second_lookup" | tier_field)" \
       && "$first_points" == "$(printf '%s' "$second_lookup" | points_field)" ]] && echo 0 || echo 1)"
fi
after_award_count=$(mysql_exec "SELECT COUNT(*) FROM coupon_award WHERE event_id=$event1")
after_issued_quantity=$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event1")
check "2 repeated lookups/retries create no new coupon_award rows" \
  "$([[ "$before_award_count" == "$after_award_count" ]] && echo 0 || echo 1)"
check "2 repeated lookups/retries do not re-decrement issued_quantity" \
  "$([[ "$before_issued_quantity" == "$after_issued_quantity" ]] && echo 0 || echo 1)"

# 3. 처리 중 강제 실패 -> 재고·쿠폰·신청 상태가 함께 롤백되고, 선행 신청을 건너뛰지 않는다.
event3=$(create_event "issuance-rollback" "UTC_TIMESTAMP(6)" 10 5 10000 5000 | tail -1)
mysql_exec "CREATE TRIGGER coupon_award_fail_before_insert BEFORE INSERT ON coupon_award FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced issuance failure'"
admit_all "$event3" 201 203
sleep 2
blocked_stats=$(mysql_exec "SELECT status FROM coupon_application WHERE event_id=$event3 AND acceptance_sequence=1")
check "3 during forced failure, sequence-1 application stays PENDING" \
  "$([[ "$blocked_stats" == "PENDING" ]] && echo 0 || echo 1)"
check "3 during forced failure, no coupon_award row exists" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award WHERE event_id=$event3")" == "0" ]] && echo 0 || echo 1)"
check "3 during forced failure, issued_quantity stays 0" \
  "$([[ "$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event3")" == "0" ]] && echo 0 || echo 1)"
mysql_exec "DROP TRIGGER coupon_award_fail_before_insert"
check "3 after removing the trigger, all 3 applications resolve" \
  "$(wait_for_no_pending "$event3" 30 && echo 0 || echo 1)"
final3=$(mysql_exec "SELECT SUM(status='ISSUED'), SUM(status='SOLD_OUT') FROM coupon_application WHERE event_id=$event3")
check "3 all 3 sequence-ordered applications end up ISSUED (event capacity 10 >= 3)" \
  "$([[ "$final3" == $'3\t0' ]] && echo 0 || echo 1)"

# 4. 원자성 보완: 3번은 첫 쓰기(coupon_award INSERT)에서 실패시켜, 이미 쓴 데이터가 있는 상태에서도
#    함께 롤백되는지는 증명하지 못했다. 이번에는 coupon_award INSERT와 issued_quantity 증가가 모두
#    끝난 뒤, 신청을 ISSUED로 바꾸는 마지막 UPDATE 직전에 실패시킨다. 트리거는 coupon_application의
#    모든 UPDATE에 걸리므로(이 행사는 총수량 안에서만 접수해 markSoldOut 경로를 타지 않는다),
#    markIssued 호출 시점에만 걸린다.
event4=$(create_event "issuance-late-rollback" "UTC_TIMESTAMP(6)" 10 5 10000 5000 | tail -1)
mysql_exec "CREATE TRIGGER coupon_application_fail_before_issued BEFORE UPDATE ON coupon_application FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced issuance failure before marking issued'"
admit_all "$event4" 401 403
sleep 2
check "4 during late-stage failure, sequence-1 application stays PENDING" \
  "$([[ "$(mysql_exec "SELECT status FROM coupon_application WHERE event_id=$event4 AND acceptance_sequence=1")" == "PENDING" ]] && echo 0 || echo 1)"
check "4 during late-stage failure, sequences 2 and 3 are also untouched (not skipped ahead)" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event4 AND acceptance_sequence IN (2,3) AND status='PENDING'")" == "2" ]] && echo 0 || echo 1)"
check "4 during late-stage failure, the already-inserted coupon_award is rolled back (0 rows)" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award WHERE event_id=$event4")" == "0" ]] && echo 0 || echo 1)"
check "4 during late-stage failure, the already-incremented issued_quantity is rolled back to 0" \
  "$([[ "$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event4")" == "0" ]] && echo 0 || echo 1)"
mysql_exec "DROP TRIGGER coupon_application_fail_before_issued"
check "4 after removing the trigger, all 3 applications resolve" \
  "$(wait_for_no_pending "$event4" 30 && echo 0 || echo 1)"
final4=$(mysql_exec "SELECT SUM(status='ISSUED'), SUM(status='SOLD_OUT') FROM coupon_application WHERE event_id=$event4")
check "4 all 3 sequence-ordered applications end up ISSUED after unblocking" \
  "$([[ "$final4" == $'3\t0' ]] && echo 0 || echo 1)"
final4_award=$(mysql_exec "SELECT COUNT(*), SUM(tier='HIGH'), SUM(tier='NORMAL') FROM coupon_award WHERE event_id=$event4")
check "4 exactly 3 coupons exist after unblocking (no duplicate from the rolled-back attempt)" \
  "$([[ "$final4_award" == $'3\t3\t0' ]] && echo 0 || echo 1)"

# 5. 발급이 진행되는 도중 앱 프로세스를 강제 종료·재시작해도 최종 결과가 유지되는지 확인한다.
#    이 시나리오는 "죽인 순간 특정 트랜잭션이 미커밋 상태였다"는 것을 증명하지 않는다(그 순간에
#    트랜잭션이 실제로 진행 중이었는지는 관측하지 못했다) - 확인하는 것은 "발급이 진행 중인 동안
#    앱 하나를 죽여도 재시작 뒤 중복·초과 없이 정확히 완료된다"는 최종 결과다.
event5=$(create_event "issuance-kill-restart" "UTC_TIMESTAMP(6)" 100 50 10000 5000 | tail -1)
admit_all "$event5" 301 700
kill_deadline=$((SECONDS + 20))
killed=0
while [[ $SECONDS -lt $kill_deadline ]]; do
  issued_now=$(mysql_exec "SELECT issued_quantity FROM coupon_event WHERE id=$event5")
  if [[ "$issued_now" -gt "5" && "$issued_now" -lt "95" ]]; then
    kill -9 "$app_a_pid" 2>/dev/null || true
    killed=1
    break
  fi
  sleep 0.02
done
check "5 app A was killed while issuance was in progress (issued_quantity was between 5 and 95)" "$([[ "$killed" == "1" ]] && echo 0 || echo 1)"
sleep 1
app_a_pid=$(start_app "$port_a" "$log_dir/app-$port_a-restarted.log")
wait_for_app "$port_a" "$log_dir/app-$port_a-restarted.log"
check "5 event drains to CLOSED within 60s after restart" "$(wait_for_drained "$event5" 60 && echo 0 || echo 1)"
event5_app_stats=$(mysql_exec "SELECT SUM(status='ISSUED'), SUM(status='SOLD_OUT'), SUM(status='PENDING'), COUNT(*) FROM coupon_application WHERE event_id=$event5")
check "5 final split is 100 ISSUED / 300 SOLD_OUT / 0 PENDING / 400 total" \
  "$([[ "$event5_app_stats" == $'100\t300\t0\t400' ]] && echo 0 || echo 1)"
event5_award_stats=$(mysql_exec "SELECT COUNT(*), COUNT(DISTINCT application_id), SUM(tier='HIGH'), SUM(tier='NORMAL') FROM coupon_award WHERE event_id=$event5")
check "5 no duplicate/over-issuance: 100 awards, 100 distinct applications, 50 HIGH/50 NORMAL" \
  "$([[ "$event5_award_stats" == $'100\t100\t50\t50' ]] && echo 0 || echo 1)"
event5_sequence_check=$(mysql_exec "SELECT COUNT(*), MIN(acceptance_sequence), MAX(acceptance_sequence) FROM coupon_application WHERE event_id=$event5")
check "5 acceptance sequences remain contiguous 1..400 across the restart" \
  "$([[ "$event5_sequence_check" == $'400\t1\t400' ]] && echo 0 || echo 1)"

# 6. 종료 후 기존 신청 조회는 정상 동작, 새 신청은 거절.
issued_lookup=$(get_me "$port_a" "$event1" "$issued_member")
check "6 an already-ISSUED member's lookup still returns ISSUED after CLOSED" \
  "$([[ "$(printf '%s' "$issued_lookup" | status_field)" == "ISSUED" ]] && echo 0 || echo 1)"
soldout_lookup=$(get_me "$port_b" "$event1" "$soldout_member")
check "6 an already-SOLD_OUT member's lookup still returns SOLD_OUT after CLOSED" \
  "$([[ "$(printf '%s' "$soldout_lookup" | status_field)" == "SOLD_OUT" ]] && echo 0 || echo 1)"
new_member_code=$(post_code "$port_a" "$event1" 999999)
check "6 a brand-new member is rejected on a CLOSED event" "$([[ "$new_member_code" == "409" ]] && echo 0 || echo 1)"

if [[ "$fail_count" -eq 0 ]]; then
  echo "PASS: all coupon issuance validations completed against two Spring Boot processes and one MySQL database."
else
  echo "FAIL: $fail_count issuance validation(s) failed." >&2
fi
echo "Evidence: $log_dir"
exit "$fail_count"
