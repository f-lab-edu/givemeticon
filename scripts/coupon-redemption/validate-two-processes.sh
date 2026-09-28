#!/usr/bin/env bash
set -euo pipefail

# 쿠폰 사용(redeem)과 중복 적립 방지 검증이다. 실제 MySQL 하나에 독립 Spring Boot 프로세스
# 두 개를 연결한다. 접수·발급은 7단계에서 이미 검증했으므로, 이 스크립트는 SQL로 직접
# coupon_application/coupon_award 픽스처를 만들어 사용(redeem) 트랜잭션 자체에 집중한다.
# 기존 givemeticon DB, 다른 단계의 검증 DB는 건드리지 않고 전용 DB만 생성/삭제한다.

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
mysql_container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_REDEMPTION_TEST_DB:-givemeticon_coupon_redemption_validation}
port_a=${COUPON_REDEMPTION_PORT_A:-18110}
port_b=${COUPON_REDEMPTION_PORT_B:-18111}
log_dir="$repo_dir/build/coupon-redemption-validation"
mkdir -p "$log_dir"

if ! docker ps --format '{{.Names}}' | grep -qx "$mysql_container"; then
  echo "MySQL container '$mysql_container' is not running." >&2
  exit 1
fi

mysql_password=$(docker exec "$mysql_container" printenv MYSQL_ROOT_PASSWORD)
app_a_pid=''
app_b_pid=''

cleanup() {
  # gradlew bootRun forks the actual JVM as a child process - killing only the wrapper PID
  # leaves that JVM (and the port) alive, so a later run can hit a stale, unpatched app
  # instance instead of freshly built code. Kill by listening port instead.
  for port in "$port_a" "$port_b"; do
    lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
  done
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
docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260923_2__add_coupon_award_redemption.sql"

start_app() {
  local port=$1 logfile=$2
  (
    cd "$repo_dir"
    SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/$database" \
    SPRING_DATASOURCE_USERNAME=root \
    SPRING_DATASOURCE_PASSWORD="$mysql_password" \
    ./gradlew bootRun --args="--server.port=$port --spring.profiles.active=local,coupon-admission,coupon-admission-test --spring.flyway.enabled=false"
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

# 이전 실행이 비정상 종료해 포트를 여전히 붙잡고 있으면(§cleanup 참고) 새로 띄우는 앱이 아니라
# 그 낡은 프로세스가 요청에 응답해 오탐을 낼 수 있다 - 시작 전에 선제적으로 정리한다.
for port in "$port_a" "$port_b"; do
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
done

app_a_pid=$(start_app "$port_a" "$log_dir/app-$port_a.log")
app_b_pid=$(start_app "$port_b" "$log_dir/app-$port_b.log")
wait_for_app "$port_a" "$log_dir/app-$port_a.log"
wait_for_app "$port_b" "$log_dir/app-$port_b.log"

create_event() {
  local public_id=$1
  mysql_exec "INSERT INTO coupon_event (public_id, status, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at) VALUES ('$public_id', 'OPEN', UTC_TIMESTAMP(6), 100, 50, 10000, 5000, UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();"
}

# 접수·발급은 7단계에서 이미 검증했으므로, 여기서는 이미 ISSUED로 확정된 신청·쿠폰 픽스처를
# SQL로 직접 만든다. issued_at_expr는 "적립에 시간 창 조건이 없다"는 것을 증명하기 위해 발급
# 시각을 임의로 과거로 앞당기는 데 쓴다(시나리오 4).
seed_issued_award() {
  local event_id=$1 member_id=$2 sequence=$3 tier=$4 points=$5 issued_at_expr=$6
  local application_id
  application_id=$(mysql_exec "INSERT INTO coupon_application (public_request_id, event_id, member_id, acceptance_sequence, status, accepted_at, finalized_at) VALUES (UUID(), $event_id, $member_id, $sequence, 'ISSUED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")
  mysql_exec "INSERT INTO coupon_award (application_id, event_id, member_id, tier, points, status, issued_at) VALUES ($application_id, $event_id, $member_id, '$tier', $points, 'ISSUED', $issued_at_expr)"
}

post_redeem() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/coupon/redeem"
}
post_redeem_code() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/coupon/redeem"
}

status_field() { jq -r '.data.status'; }
redeemed_at_field() { jq -r '.data.redeemedAt'; }
points_earned_field() { jq -r '.data.pointsEarned'; }
earned_amount_field() { jq -r '.data.earnedPointsAmount'; }

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

# 1. 발급 직후 사용 -> REDEEMED, 쿠폰의 액면가(coupon_award.points, 고액=10000)만큼 1회 적립, 잔액 반영.
event1=$(create_event "redeem-within-window" | tail -1)
seed_issued_award "$event1" 1001 1 HIGH 10000 "UTC_TIMESTAMP(6)"
resp1=$(post_redeem "$port_a" "$event1" 1001)
check "1 redeem response status is REDEEMED" "$([[ "$(printf '%s' "$resp1" | status_field)" == "REDEEMED" ]] && echo 0 || echo 1)"
check "1 redeem response reports pointsEarned=true" "$([[ "$(printf '%s' "$resp1" | points_earned_field)" == "true" ]] && echo 0 || echo 1)"
check "1 redeem response earnedPointsAmount is 10000" "$([[ "$(printf '%s' "$resp1" | earned_amount_field)" == "10000" ]] && echo 0 || echo 1)"
check "1 exactly one earn-history row exists for this coupon" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event1 AND a.member_id=1001")" == "1" ]] && echo 0 || echo 1)"
check "1 member_point_balance for member 1001 is exactly 10000" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=1001")" == "10000" ]] && echo 0 || echo 1)"
check "1 coupon_award status in DB is REDEEMED" \
  "$([[ "$(mysql_exec "SELECT status FROM coupon_award WHERE event_id=$event1 AND member_id=1001")" == "REDEEMED" ]] && echo 0 || echo 1)"

# 2. 재시도(같은 요청 반복) -> 같은 결과, 적립·잔액 불변.
resp1_retry=$(post_redeem "$port_b" "$event1" 1001)
first_redeemed_at=$(printf '%s' "$resp1" | redeemed_at_field)
retry_redeemed_at=$(printf '%s' "$resp1_retry" | redeemed_at_field)
if require_field "2 first response has a non-null redeemedAt" "$first_redeemed_at" \
  && require_field "2 retry response has a non-null redeemedAt" "$retry_redeemed_at"; then
  check "2 retry returns the exact same redeemedAt (idempotent, not re-redeemed)" \
    "$([[ "$first_redeemed_at" == "$retry_redeemed_at" ]] && echo 0 || echo 1)"
fi
check "2 retry still reports pointsEarned=true with the same amount" \
  "$([[ "$(printf '%s' "$resp1_retry" | points_earned_field)" == "true" && "$(printf '%s' "$resp1_retry" | earned_amount_field)" == "10000" ]] && echo 0 || echo 1)"
check "2 retry creates no additional earn-history row (still exactly 1)" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event1 AND a.member_id=1001")" == "1" ]] && echo 0 || echo 1)"
check "2 retry does not re-increment the balance (still exactly 10000)" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=1001")" == "10000" ]] && echo 0 || echo 1)"

# 3. 두 앱의 동시 사용 요청(같은 쿠폰) -> 정확히 한 번만 적립.
event3=$(create_event "redeem-concurrent" | tail -1)
seed_issued_award "$event3" 2001 1 HIGH 10000 "UTC_TIMESTAMP(6)"
post_redeem "$port_a" "$event3" 2001 >"$log_dir/concurrent-a.json" & pid_a=$!
post_redeem "$port_b" "$event3" 2001 >"$log_dir/concurrent-b.json" & pid_b=$!
wait "$pid_a" "$pid_b"
concurrent_a_status=$(status_field < "$log_dir/concurrent-a.json")
concurrent_b_status=$(status_field < "$log_dir/concurrent-b.json")
check "3 both concurrent responses report REDEEMED" \
  "$([[ "$concurrent_a_status" == "REDEEMED" && "$concurrent_b_status" == "REDEEMED" ]] && echo 0 || echo 1)"
concurrent_a_at=$(redeemed_at_field < "$log_dir/concurrent-a.json")
concurrent_b_at=$(redeemed_at_field < "$log_dir/concurrent-b.json")
if require_field "3 app A response has a non-null redeemedAt" "$concurrent_a_at" \
  && require_field "3 app B response has a non-null redeemedAt" "$concurrent_b_at"; then
  check "3 both concurrent responses agree on the same redeemedAt (one winner)" \
    "$([[ "$concurrent_a_at" == "$concurrent_b_at" ]] && echo 0 || echo 1)"
fi
check "3 both concurrent responses report pointsEarned=true (one earned, one saw it)" \
  "$([[ "$(points_earned_field < "$log_dir/concurrent-a.json")" == "true" && "$(points_earned_field < "$log_dir/concurrent-b.json")" == "true" ]] && echo 0 || echo 1)"
check "3 exactly one earn-history row exists despite two concurrent requests" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event3 AND a.member_id=2001")" == "1" ]] && echo 0 || echo 1)"
check "3 member 2001's balance was incremented exactly once (10000, not 20000)" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=2001")" == "10000" ]] && echo 0 || echo 1)"

# 4. 발급 후 8일이 지나 사용해도 적립에는 시간 창이 없다 - 그대로 REDEEMED + 적립되고, 적립액은
#    고정값이 아니라 이 쿠폰(일반 tier) 자신의 액면가(5000)다.
event4=$(create_event "redeem-long-after-issuance" | tail -1)
seed_issued_award "$event4" 3001 1 NORMAL 5000 "UTC_TIMESTAMP(6) - INTERVAL 8 DAY"
resp4=$(post_redeem "$port_a" "$event4" 3001)
check "4 redeem 8 days after issuance still succeeds (status REDEEMED, no time window)" \
  "$([[ "$(printf '%s' "$resp4" | status_field)" == "REDEEMED" ]] && echo 0 || echo 1)"
check "4 redeem 8 days after issuance still earns points (pointsEarned=true)" \
  "$([[ "$(printf '%s' "$resp4" | points_earned_field)" == "true" ]] && echo 0 || echo 1)"
check "4 earned amount is this coupon's own face value (5000 for NORMAL tier, not a flat amount)" \
  "$([[ "$(printf '%s' "$resp4" | earned_amount_field)" == "5000" ]] && echo 0 || echo 1)"
check "4 exactly one earn-history row of amount 5000 exists" \
  "$([[ "$(mysql_exec "SELECT amount FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event4 AND a.member_id=3001")" == "5000" ]] && echo 0 || echo 1)"
check "4 member 3001's balance is exactly 5000" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=3001")" == "5000" ]] && echo 0 || echo 1)"

# 5. 원자성: 마지막 쓰기(member_point_balance)를 강제로 실패시켜, 이미 쓴 앞의 두 변경
#    (coupon_award 상태, 적립 내역)까지 함께 롤백되는지 확인한다.
event5=$(create_event "redeem-atomic-rollback" | tail -1)
seed_issued_award "$event5" 4001 1 HIGH 10000 "UTC_TIMESTAMP(6)"
mysql_exec "CREATE TRIGGER member_point_balance_fail_before_insert BEFORE INSERT ON member_point_balance FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced balance update failure'"
redeem_fail_code=$(post_redeem_code "$port_a" "$event5" 4001)
check "5 forced balance-write failure surfaces as an error status" "$([[ "$redeem_fail_code" -ge "500" ]] && echo 0 || echo 1)"
check "5 during forced failure, coupon_award stays ISSUED (not REDEEMED)" \
  "$([[ "$(mysql_exec "SELECT status FROM coupon_award WHERE event_id=$event5 AND member_id=4001")" == "ISSUED" ]] && echo 0 || echo 1)"
check "5 during forced failure, no earn-history row was left behind" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event5 AND a.member_id=4001")" == "0" ]] && echo 0 || echo 1)"
check "5 during forced failure, no balance row was left behind" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM member_point_balance WHERE member_id=4001")" == "0" ]] && echo 0 || echo 1)"
mysql_exec "DROP TRIGGER member_point_balance_fail_before_insert"
resp5_retry=$(post_redeem "$port_a" "$event5" 4001)
check "5 after removing the trigger, retry succeeds cleanly (REDEEMED, pointsEarned=true)" \
  "$([[ "$(printf '%s' "$resp5_retry" | status_field)" == "REDEEMED" && "$(printf '%s' "$resp5_retry" | points_earned_field)" == "true" ]] && echo 0 || echo 1)"
check "5 after recovery, exactly one earn-history row and one balance of 10000 exist" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event5 AND a.member_id=4001")" == "1" \
     && "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=4001")" == "10000" ]] && echo 0 || echo 1)"

# 6. 발급된 적 없는 쿠폰을 사용하려 하면 404.
event6=$(create_event "redeem-not-found" | tail -1)
not_found_code=$(post_redeem_code "$port_a" "$event6" 9999)
check "6 redeeming a never-issued coupon returns 404" "$([[ "$not_found_code" == "404" ]] && echo 0 || echo 1)"

# 7. 같은 회원이 서로 다른 행사의 쿠폰(고액 10000 + 일반 5000) 두 장을 동시에 사용 -> 잔액에
#    각 쿠폰의 액면가가 정확히 합산된다(10000+5000=15000, member_point_balance의 UPSERT
#    `balance = balance + ?`가 손실 갱신 없이 직렬화되는지 확인).
event7a=$(create_event "redeem-concurrent-member-a" | tail -1)
event7b=$(create_event "redeem-concurrent-member-b" | tail -1)
seed_issued_award "$event7a" 5001 1 HIGH 10000 "UTC_TIMESTAMP(6)"
seed_issued_award "$event7b" 5001 1 NORMAL 5000 "UTC_TIMESTAMP(6)"
post_redeem "$port_a" "$event7a" 5001 >"$log_dir/concurrent-cross-event-a.json" & pid_7a=$!
post_redeem "$port_b" "$event7b" 5001 >"$log_dir/concurrent-cross-event-b.json" & pid_7b=$!
wait "$pid_7a" "$pid_7b"
check "7 both cross-event concurrent responses report REDEEMED" \
  "$([[ "$(status_field < "$log_dir/concurrent-cross-event-a.json")" == "REDEEMED" && "$(status_field < "$log_dir/concurrent-cross-event-b.json")" == "REDEEMED" ]] && echo 0 || echo 1)"
check "7 both coupons each recorded their own earn-history row (2 total)" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.member_id=5001")" == "2" ]] && echo 0 || echo 1)"
check "7 member 5001's balance is the exact sum of both coupons' face values (15000 = 10000+5000, no lost update)" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=5001")" == "15000" ]] && echo 0 || echo 1)"

# 8. 다른 회원의 쿠폰 사용은 거절되고 데이터가 변경되지 않는다. event8의 쿠폰은 member 6001에게
#    발급돼 있다 - member 6002(session 대역)로 같은 event를 사용 요청하면 본인 쿠폰이 아니므로
#    (event_id+member_id로 조회하는 findByEventIdAndMemberId가 자기 자신의 memberId만 조회
#    하도록 강제한다) 404를 받고, 실제 소유자(6001)의 쿠폰 상태·잔액은 그대로여야 한다.
event8=$(create_event "redeem-other-member" | tail -1)
seed_issued_award "$event8" 6001 1 HIGH 10000 "UTC_TIMESTAMP(6)"
other_member_code=$(post_redeem_code "$port_a" "$event8" 6002)
check "8 another member's redeem attempt on someone else's coupon returns 404" \
  "$([[ "$other_member_code" == "404" ]] && echo 0 || echo 1)"
check "8 the real owner's coupon_award is untouched (still ISSUED)" \
  "$([[ "$(mysql_exec "SELECT status FROM coupon_award WHERE event_id=$event8 AND member_id=6001")" == "ISSUED" ]] && echo 0 || echo 1)"
check "8 no earn-history row was created for the rejected attempt" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event8")" == "0" ]] && echo 0 || echo 1)"
check "8 no balance row exists for either member (6001 untouched, 6002 never had one)" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM member_point_balance WHERE member_id IN (6001, 6002)")" == "0" ]] && echo 0 || echo 1)"

# 9. 커밋 후 응답 유실: 클라이언트가 극단적으로 짧은 타임아웃으로 응답을 받기 전에 연결을 끊어도
#    서버는 이미 커밋을 마쳤다 - 그 뒤 정상적으로 재요청하면 추가 적립 없이 최초 결과를 그대로
#    반환하는지 확인한다(재시도 경로는 시나리오 2와 동일하지만, 여기서는 "응답을 못 받은 최초
#    요청" 자체를 실제로 재현한다).
event9=$(create_event "redeem-response-lost" | tail -1)
seed_issued_award "$event9" 7001 1 HIGH 10000 "UTC_TIMESTAMP(6)"
set +e
curl --silent --max-time 0.001 -X POST -H "X-Coupon-Admission-Test-Member: 7001" \
  "http://localhost:$port_a/test-support/coupon-events/$event9/coupon/redeem" >/dev/null 2>&1
lost_request_curl_exit=$?
set -e
# curl 28 = "Operation timeout" - 서버가 응답을 쓰기 전에 클라이언트가 실제로 연결을 끊었다는
# 증거다. 다른 코드가 나오면 "응답 유실을 재현했다"는 전제 자체가 깨진 것이므로 실패로 잡는다.
check "9 the first request actually timed out client-side (curl exit 28), proving a lost response" \
  "$([[ "$lost_request_curl_exit" == "28" ]] && echo 0 || echo 1)"
for _ in $(seq 1 50); do
  redeemed_status=$(mysql_exec "SELECT status FROM coupon_award WHERE event_id=$event9 AND member_id=7001")
  [[ "$redeemed_status" == "REDEEMED" ]] && break
  sleep 0.1
done
check "9 the response-lost request still committed server-side (coupon_award is REDEEMED)" \
  "$([[ "$redeemed_status" == "REDEEMED" ]] && echo 0 || echo 1)"
check "9 exactly one earn-history row exists after the lost-response request" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event9 AND a.member_id=7001")" == "1" ]] && echo 0 || echo 1)"
resp9_retry=$(post_redeem "$port_b" "$event9" 7001)
check "9 the retry after the lost response returns the same REDEEMED result, no re-earn" \
  "$([[ "$(printf '%s' "$resp9_retry" | status_field)" == "REDEEMED" ]] && echo 0 || echo 1)"
check "9 retry still reports the original earnedPointsAmount without creating a second row" \
  "$([[ "$(printf '%s' "$resp9_retry" | earned_amount_field)" == "10000" \
     && "$(mysql_exec "SELECT COUNT(*) FROM coupon_award_earn_history h JOIN coupon_award a ON a.id=h.coupon_award_id WHERE a.event_id=$event9 AND a.member_id=7001")" == "1" ]] && echo 0 || echo 1)"
check "9 balance for member 7001 was incremented exactly once (10000, not 20000)" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=7001")" == "10000" ]] && echo 0 || echo 1)"

if [[ "$fail_count" -eq 0 ]]; then
  echo "PASS: all coupon redemption validations completed against two Spring Boot processes and one MySQL database."
else
  echo "FAIL: $fail_count redemption validation(s) failed." >&2
fi
echo "Evidence: $log_dir"
exit "$fail_count"
