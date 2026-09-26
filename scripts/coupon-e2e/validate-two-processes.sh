#!/usr/bin/env bash
set -euo pipefail

# 접수 -> 발급 -> 조회 -> 사용 -> 잔액 반영을 처음부터 끝까지 실제 API로 연결하는 소규모
# 통합 검증이다. 3~5단계(접수), 7단계(발급), 8단계(사용/적립)는 각자 SQL로 만든 픽스처를 두고
# 자기 트랜잭션만 따로 검증했다 - 이 스크립트는 그 픽스처를 걷어내고, 실제 POST 접수 요청이
# 실제 발급 워커를 거쳐 실제 coupon_award로 이어지고, 그 award.points가 실제 사용(redeem)
# 응답의 적립액과 정확히 일치하는지를 SQL 개입 없이 확인한다.
#
# 동시 접수 순서 보존, 재고 소진(SOLD_OUT) 판정, 동시 사용 요청, 강제 실패 롤백, 응답 유실
# 같은 장애·경합 시나리오는 각 단계 문서(03/07/08)에서 이미 대량의 요청으로 검증했으므로
# 여기서 반복하지 않는다 - 이 스크립트는 "단계 사이의 연결"만 확인하는 소규모 검증이다.
# (재고 소진처럼 워커가 아주 빠르게 반응해야 재현되는 경합은, 이 스크립트처럼 회원 2명만 쓰는
# 작은 규모에서는 접수 3번째 요청이 그 사이 이미 CLOSED된 행사에 막혀 버려 결정적으로 재현하기
# 어렵다 - 그래서 여기서는 시도하지 않는다.)
#
# 기존 givemeticon DB, 다른 단계의 검증 DB는 건드리지 않는다. 전용 DB만 생성/삭제한다.

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
mysql_container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_E2E_TEST_DB:-givemeticon_coupon_e2e_validation}
port_a=${COUPON_E2E_PORT_A:-18120}
port_b=${COUPON_E2E_PORT_B:-18121}
log_dir="$repo_dir/build/coupon-e2e-validation"
mkdir -p "$log_dir"

if ! docker ps --format '{{.Names}}' | grep -qx "$mysql_container"; then
  echo "MySQL container '$mysql_container' is not running." >&2
  exit 1
fi

mysql_password=$(docker exec "$mysql_container" printenv MYSQL_ROOT_PASSWORD)
app_a_pid=''
app_b_pid=''

cleanup() {
  # gradlew bootRun의 자식 JVM은 wrapper만 죽여서는 포트에 남을 수 있다(8단계 검증에서 실제로
  # 겪은 문제) - 포트 기준으로 확실히 정리한다.
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

for port in "$port_a" "$port_b"; do
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
done

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
  && echo "confirmed: issuance worker bean registered on app A" \
  || { echo "issuance worker bean not found on app A" >&2; exit 1; }

create_event() {
  local public_id=$1 total_quantity=$2 high_quantity=$3 high_points=$4 normal_points=$5
  mysql_exec "INSERT INTO coupon_event (public_id, status, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at) VALUES ('$public_id', 'OPEN', UTC_TIMESTAMP(6), $total_quantity, $high_quantity, $high_points, $normal_points, UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();"
}

accept() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications"
}
get_me() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications/me"
}
redeem() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/coupon/redeem"
}

request_id() { jq -r '.data.requestId'; }
admission_status_field() { jq -r '.data.status'; }
tier_field() { jq -r '.data.couponTier'; }
points_field() { jq -r '.data.couponPoints'; }
redeem_status_field() { jq -r '.data.status'; }
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

wait_for_issued() {
  # 실제 비동기 발급 워커(200ms 주기)가 이 신청을 ISSUED로 확정할 때까지 기다린다 - SQL로
  # 미리 만들지 않고, 실제 워커가 처리한 결과를 그대로 읽는다.
  local port=$1 event_id=$2 member_id=$3 timeout_secs=${4:-30}
  local deadline=$((SECONDS + timeout_secs)) resp status
  while [[ $SECONDS -lt $deadline ]]; do
    resp=$(get_me "$port" "$event_id" "$member_id")
    status=$(printf '%s' "$resp" | admission_status_field)
    [[ "$status" == "ISSUED" ]] && { printf '%s' "$resp"; return 0; }
    sleep 0.2
  done
  return 1
}

# 이벤트: 고액 1장 + 일반 1장, 정확히 회원 2명만 접수한다. 1번째로 접수한 회원이 고액(10000),
# 2번째가 일반(5000)을 받는다 - 순서는 순차 접수로 결정적으로 만든다.
event=$(create_event "e2e-accept-to-redeem" 2 1 10000 5000 | tail -1)

member_high=9001
member_normal=9002

# 1. 접수(POST, 실제 API) -> 발급 워커가 실제로 처리 -> 조회(GET, 실제 API)로 확인한다.
accept_high=$(accept "$port_a" "$event" "$member_high")
accept_high_reqid=$(printf '%s' "$accept_high" | request_id)
require_field "1 accept response for member_high has a non-null requestId" "$accept_high_reqid"

issued_high=$(wait_for_issued "$port_a" "$event" "$member_high" 30) \
  && check "1 member_high's application reaches ISSUED via the real async worker within 30s" 0 \
  || check "1 member_high's application reaches ISSUED via the real async worker within 30s" 1
high_tier=$(printf '%s' "$issued_high" | tier_field)
high_points=$(printf '%s' "$issued_high" | points_field)
check "1 member_high (accepted first) was actually issued a HIGH tier coupon" \
  "$([[ "$high_tier" == "HIGH" ]] && echo 0 || echo 1)"
check "1 member_high's real issued coupon carries the event's high_points (10000)" \
  "$([[ "$high_points" == "10000" ]] && echo 0 || echo 1)"

accept_normal=$(accept "$port_b" "$event" "$member_normal")
accept_normal_reqid=$(printf '%s' "$accept_normal" | request_id)
require_field "1 accept response for member_normal has a non-null requestId" "$accept_normal_reqid"

issued_normal=$(wait_for_issued "$port_b" "$event" "$member_normal" 30) \
  && check "1 member_normal's application reaches ISSUED via the real async worker within 30s" 0 \
  || check "1 member_normal's application reaches ISSUED via the real async worker within 30s" 1
normal_tier=$(printf '%s' "$issued_normal" | tier_field)
normal_points=$(printf '%s' "$issued_normal" | points_field)
check "1 member_normal (accepted second, event capacity exhausted for HIGH) was issued a NORMAL tier coupon" \
  "$([[ "$normal_tier" == "NORMAL" ]] && echo 0 || echo 1)"
check "1 member_normal's real issued coupon carries the event's normal_points (5000)" \
  "$([[ "$normal_points" == "5000" ]] && echo 0 || echo 1)"

# 2. 사용(POST redeem, 실제 API) -> 적립액이 방금 실제로 발급된 award.points와 정확히 일치하는지 확인.
#    (SQL 픽스처가 아니라 위 1번에서 실제 워커가 만든 쿠폰을 그대로 사용한다.)
redeem_high=$(redeem "$port_b" "$event" "$member_high")
check "2 redeem for member_high succeeds (status REDEEMED)" \
  "$([[ "$(printf '%s' "$redeem_high" | redeem_status_field)" == "REDEEMED" ]] && echo 0 || echo 1)"
check "2 member_high earns points on redeem" \
  "$([[ "$(printf '%s' "$redeem_high" | points_earned_field)" == "true" ]] && echo 0 || echo 1)"
check "2 member_high's earnedPointsAmount matches the real issued coupon's points (10000, not a hardcoded value)" \
  "$([[ "$(printf '%s' "$redeem_high" | earned_amount_field)" == "$high_points" ]] && echo 0 || echo 1)"
check "2 member_high's member_point_balance is exactly the earned amount (10000)" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=$member_high")" == "$high_points" ]] && echo 0 || echo 1)"

redeem_normal=$(redeem "$port_a" "$event" "$member_normal")
check "2 redeem for member_normal succeeds (status REDEEMED)" \
  "$([[ "$(printf '%s' "$redeem_normal" | redeem_status_field)" == "REDEEMED" ]] && echo 0 || echo 1)"
check "2 member_normal earns points on redeem" \
  "$([[ "$(printf '%s' "$redeem_normal" | points_earned_field)" == "true" ]] && echo 0 || echo 1)"
check "2 member_normal's earnedPointsAmount matches the real issued coupon's points (5000, not the HIGH amount)" \
  "$([[ "$(printf '%s' "$redeem_normal" | earned_amount_field)" == "$normal_points" ]] && echo 0 || echo 1)"
check "2 member_normal's member_point_balance is exactly the earned amount (5000)" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=$member_normal")" == "$normal_points" ]] && echo 0 || echo 1)"

# 3. 체인 전체가 멱등한지 확인: 접수 재요청·사용 재요청 모두 새 쓰기 없이 같은 결과를 반환한다.
retry_accept=$(accept "$port_a" "$event" "$member_high")
check "3 re-accepting an already-issued member returns the same requestId (no new application)" \
  "$([[ "$(printf '%s' "$retry_accept" | request_id)" == "$accept_high_reqid" ]] && echo 0 || echo 1)"
retry_redeem=$(redeem "$port_a" "$event" "$member_high")
check "3 retrying redeem for member_high returns the same redeemedAt (no re-redeem, no re-earn)" \
  "$([[ "$(printf '%s' "$retry_redeem" | redeemed_at_field)" == "$(printf '%s' "$redeem_high" | redeemed_at_field)" ]] && echo 0 || echo 1)"
check "3 retrying redeem does not change member_high's balance (still exactly 10000)" \
  "$([[ "$(mysql_exec "SELECT balance FROM member_point_balance WHERE member_id=$member_high")" == "10000" ]] && echo 0 || echo 1)"

if [[ "$fail_count" -eq 0 ]]; then
  echo "PASS: all coupon accept-to-redeem end-to-end validations completed against two Spring Boot processes and one MySQL database."
else
  echo "FAIL: $fail_count end-to-end validation(s) failed." >&2
fi
echo "Evidence: $log_dir"
exit "$fail_count"
