#!/usr/bin/env bash
set -euo pipefail

# 실제 MySQL 하나에 독립 Spring Boot 프로세스 두 개를 연결해 접수 원장을 검증한다.
# 기존 givemeticon DB와 쿠폰 실험 테이블은 건드리지 않고 전용 DB만 생성/삭제한다.
#
# 정정 이력(6단계 재검증, docs/coupon/06-batch-admission-hypothesis-and-loadtest.md 정정판 참고):
# 이전 버전은 응답 본문을 감싸는 전역 {"message":"SUCCESS","data":{...}} 래퍼를 반영하지 않고
# `.requestId`/`.acceptanceSequence` 경로로 jq를 호출해, 실제로는 항상 null을 비교했다. 또한 macOS
# 기본 bash(3.2)에서는 `set -e`가 실패한 `[[ ... ]]` 단독 문에서 스크립트를 중단시키지 않아, 두 null이
# 우연히 같다는 이유로 통과가 났을 가능성이 있었다(예: 시나리오 2/3/7). 과거 "PASS: 7/7" 기록(3단계
# 문서)은 삭제하지 않되, 이 결함이 있었다는 사실과 아래 수정 후 재검증 결과를 함께 남긴다.
#
# 이번 버전은 (1) `.data.*` 경로로 수정하고 (2) 필수 필드가 비어있거나 "null"이면 즉시 실패로
# 기록하는 require_field를 추가했으며 (3) `[[ ]]`에 의존하지 않는 명시적 check() 카운터로 결과를
# 집계해, 마지막에 실패 건수를 종료 코드로 반환한다.

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
mysql_container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_ADMISSION_TEST_DB:-givemeticon_coupon_admission_validation}
port_a=${COUPON_ADMISSION_PORT_A:-18080}
port_b=${COUPON_ADMISSION_PORT_B:-18081}
log_dir="$repo_dir/build/coupon-admission-validation"
mkdir -p "$log_dir"

if ! docker ps --format '{{.Names}}' | grep -qx "$mysql_container"; then
  echo "MySQL container '$mysql_container' is not running." >&2
  exit 1
fi

mysql_password=$(docker exec "$mysql_container" printenv MYSQL_ROOT_PASSWORD)
app_a_pid=''
app_b_pid=''

cleanup() {
  [[ -n "$app_a_pid" ]] && kill "$app_a_pid" 2>/dev/null || true
  [[ -n "$app_b_pid" ]] && kill "$app_b_pid" 2>/dev/null || true
}
trap cleanup EXIT

mysql_exec() {
  docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" --batch --skip-column-names -e "$1"
}

docker exec "$mysql_container" mysql -uroot -p"$mysql_password" -e "DROP DATABASE IF EXISTS \`$database\`; CREATE DATABASE \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260920__add_coupon_event_admission_ledger.sql"

start_app() {
  local port=$1
  local logfile=$2
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
  local port=$1
  local logfile=$2
  for _ in $(seq 1 90); do
    if curl --silent --fail "http://localhost:$port/actuator/health" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Application on port $port did not start. See $logfile" >&2
  return 1
}

app_a_pid=$(start_app "$port_a" "$log_dir/app-$port_a.log")
app_b_pid=$(start_app "$port_b" "$log_dir/app-$port_b.log")
wait_for_app "$port_a" "$log_dir/app-$port_a.log"
wait_for_app "$port_b" "$log_dir/app-$port_b.log"

create_event() {
  local public_id=$1
  local starts_at=$2
  mysql_exec "INSERT INTO coupon_event (public_id, status, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at) VALUES ('$public_id', 'SCHEDULED', $starts_at, 100, 50, 10000, 5000, UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();"
}

post() {
  local port=$1
  local event_id=$2
  local member_id=$3
  curl --silent --show-error --fail -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications"
}

# 컨트롤러 응답은 전역 CustomResponseBodyReturnValueHandler가 {"message":"SUCCESS","data":{...}}로
# 감싼다. jq 경로를 .data.* 로 맞추지 않으면 항상 null을 비교하게 되어 거짓 통과를 만든다.
request_id() { jq -r '.data.requestId'; }
sequence() { jq -r '.data.acceptanceSequence'; }
status_field() { jq -r '.data.status'; }

close_event() {
  local event_id=$1
  mysql_exec "UPDATE coupon_event SET status='CLOSED', closed_at=UTC_TIMESTAMP(6) WHERE id=$event_id"
}

# 종료 빠른 응답은 프로세스-로컬 캐시(CouponEventClosureCache)로 동작한다 - 그 앱이 잠금
# 트랜잭션으로 CLOSED를 실제로 관측한 적이 있어야 그 뒤부터 ENDED를 즉시 돌려준다. 실제 운영에서는
# 발급 워커가 종료시킨 뒤 들어오는 접수 트래픽이 자연스럽게 이 발견을 트리거하지만, 여기서는 그
# 첫 발견을 흉내내는 워밍업 요청을 보낸다(신규 신청이라 아직 캐시가 없으면 409로 거절되는 게
# 정상이므로 --fail을 쓰지 않는다 - 응답 자체는 검증 대상이 아니다).
warm_up_closure_discovery() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --output /dev/null -X POST -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications" || true
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

# 값이 비어있거나 문자열 "null"이면 즉시 실패로 기록한다. 두 값을 비교하기 전에 각각 실제 값을
# 담고 있는지부터 확인해, "null" == "null" 같은 우연한 통과를 막는다.
require_field() {
  local description=$1 value=$2
  if [[ -z "$value" || "$value" == "null" ]]; then
    check "$description" 1
    return 1
  fi
  return 0
}

# 1. 시작 전 접수는 원장에 남지 않는다.
prestart_event=$(create_event prestart "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE)" | tail -1)
http_code=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST -H 'X-Coupon-Admission-Test-Member: 1' "http://localhost:$port_a/test-support/coupon-events/$prestart_event/applications")
check "1 start-before rejects with 409" "$([[ "$http_code" == "409" ]] && echo 0 || echo 1)"
check "1 start-before leaves no application row" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$prestart_event")" == "0" ]] && echo 0 || echo 1)"

# 2. 같은 회원 재신청은 같은 접수번호를 돌려준다.
event_repeat=$(create_event repeat "UTC_TIMESTAMP(6)" | tail -1)
first=$(post "$port_a" "$event_repeat" 10)
retry=$(post "$port_a" "$event_repeat" 10)
first_id=$(printf '%s' "$first" | request_id)
retry_id=$(printf '%s' "$retry" | request_id)
if require_field "2 first response has a non-null requestId" "$first_id" \
  && require_field "2 retry response has a non-null requestId" "$retry_id"; then
  check "2 repeat request returns same requestId" "$([[ "$first_id" == "$retry_id" ]] && echo 0 || echo 1)"
fi
check "2 repeat request creates only one row" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_repeat AND member_id=10")" == "1" ]] && echo 0 || echo 1)"

# 3. 서로 다른 프로세스에서 같은 회원이 동시에 신청해도 한 행만 생긴다.
event_same=$(create_event same-member "UTC_TIMESTAMP(6)" | tail -1)
post "$port_a" "$event_same" 20 >"$log_dir/same-a.json" & pid_a=$!
post "$port_b" "$event_same" 20 >"$log_dir/same-b.json" & pid_b=$!
wait "$pid_a" "$pid_b"
check "3 cross-app same member yields one row" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_same AND member_id=20")" == "1" ]] && echo 0 || echo 1)"
same_a_id=$(request_id < "$log_dir/same-a.json")
same_b_id=$(request_id < "$log_dir/same-b.json")
if require_field "3 app A response has a non-null requestId" "$same_a_id" \
  && require_field "3 app B response has a non-null requestId" "$same_b_id"; then
  check "3 cross-app same member returns same requestId" "$([[ "$same_a_id" == "$same_b_id" ]] && echo 0 || echo 1)"
fi

# 4. 서로 다른 회원의 동시 신청은 중복되지 않는 순번을 만든다.
event_distinct=$(create_event distinct-member "UTC_TIMESTAMP(6)" | tail -1)
post "$port_a" "$event_distinct" 31 >"$log_dir/distinct-a.json" & pid_a=$!
post "$port_b" "$event_distinct" 32 >"$log_dir/distinct-b.json" & pid_b=$!
wait "$pid_a" "$pid_b"
distinct_stats=$(mysql_exec "SELECT COUNT(*), COUNT(DISTINCT acceptance_sequence), MIN(acceptance_sequence), MAX(acceptance_sequence) FROM coupon_application WHERE event_id=$event_distinct")
check "4 distinct members get 2 unique contiguous sequences" "$([[ "$distinct_stats" == $'2\t2\t1\t2' ]] && echo 0 || echo 1)"

# 5. 미확정 선행 트랜잭션이 행사 행을 잡으면 뒤 요청은 커밋할 수 없다.
event_block=$(create_event uncommitted-first "UTC_TIMESTAMP(6)" | tail -1)
(
  docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" <<SQL
START TRANSACTION;
SELECT id FROM coupon_event WHERE id=$event_block FOR UPDATE;
UPDATE coupon_event SET status='OPEN', next_acceptance_sequence=1 WHERE id=$event_block;
INSERT INTO coupon_application (public_request_id,event_id,member_id,acceptance_sequence,status,accepted_at)
VALUES ('manual-uncommitted-first',$event_block,41,1,'PENDING',UTC_TIMESTAMP(6));
DO SLEEP(2);
COMMIT;
SQL
) >"$log_dir/first-transaction.log" 2>&1 & first_tx_pid=$!
sleep 0.5
post "$port_b" "$event_block" 42 >"$log_dir/blocked-second.json" & second_pid=$!
sleep 0.5
if ! kill -0 "$second_pid" 2>/dev/null; then
  check "5 second request waits for uncommitted first transaction" 1
else
  check "5 second request waits for uncommitted first transaction" 0
fi
wait "$first_tx_pid" "$second_pid"
check "5 second request gets sequence 2 after first commits" "$([[ "$(sequence < "$log_dir/blocked-second.json")" == "2" ]] && echo 0 || echo 1)"

# 6. insert 실패는 순번 증가와 함께 롤백된다.
event_rollback=$(create_event rollback "UTC_TIMESTAMP(6)" | tail -1)
# 이 짧은 구간에는 실패 검증 요청만 보낸다. 복합문 delimiter 의존을 피하기 위해 단일 문 트리거를 쓴다.
mysql_exec "CREATE TRIGGER coupon_application_fail_before_insert BEFORE INSERT ON coupon_application FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced admission failure'"
http_code=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST -H 'X-Coupon-Admission-Test-Member: 999999' "http://localhost:$port_a/test-support/coupon-events/$event_rollback/applications")
check "6 forced insert failure returns 500" "$([[ "$http_code" == "500" ]] && echo 0 || echo 1)"
mysql_exec "DROP TRIGGER coupon_application_fail_before_insert"
check "6 rollback leaves sequence counter at 0" "$([[ "$(mysql_exec "SELECT next_acceptance_sequence FROM coupon_event WHERE id=$event_rollback")" == "0" ]] && echo 0 || echo 1)"
check "6 rollback leaves zero application rows" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_rollback")" == "0" ]] && echo 0 || echo 1)"

# 7. 첫 응답을 버린 뒤 재요청해도 기존 접수번호가 반환된다.
event_lost=$(create_event response-lost "UTC_TIMESTAMP(6)" | tail -1)
post "$port_a" "$event_lost" 51 >/dev/null
recovered=$(post "$port_b" "$event_lost" 51)
recovered_id=$(printf '%s' "$recovered" | request_id)
stored=$(mysql_exec "SELECT public_request_id FROM coupon_application WHERE event_id=$event_lost AND member_id=51")
if require_field "7 retry response has a non-null requestId" "$recovered_id" \
  && require_field "7 DB has a stored requestId for this member" "$stored"; then
  check "7 retry after lost response returns the committed requestId" "$([[ "$recovered_id" == "$stored" ]] && echo 0 || echo 1)"
fi

# 8. 종료 직전 동시 신청: 이 행사는 아직 어느 앱에서도 종료가 발견된 적이 없으므로(캐시 비어
#    있음) 신규 신청은 항상 기존 잠금 트랜잭션 경로로 들어간다. 다른 트랜잭션이 행사 행을 잠근 채
#    CLOSED로 전환하려는 동안(아직 커밋 전) 들어온 신청이 잠금 해제를 기다렸다가, 실제로 커밋된
#    CLOSED를 다시 검증해 거절하는지 확인한다(빠른 경로 도입이 진행 중인 행사의 잠금 재검증을
#    건너뛰게 만들지 않았다는 확인).
event_close_race=$(create_event close-race "UTC_TIMESTAMP(6)" | tail -1)
(
  docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" <<SQL
START TRANSACTION;
SELECT id FROM coupon_event WHERE id=$event_close_race FOR UPDATE;
UPDATE coupon_event SET status='OPEN', next_acceptance_sequence=1 WHERE id=$event_close_race;
DO SLEEP(1);
UPDATE coupon_event SET status='CLOSED', closed_at=UTC_TIMESTAMP(6) WHERE id=$event_close_race;
COMMIT;
SQL
) >"$log_dir/close-race-tx.log" 2>&1 & close_tx_pid=$!
sleep 0.3
http_code=$(curl --silent --output "$log_dir/close-race-during.json" --write-out '%{http_code}' -X POST -H 'X-Coupon-Admission-Test-Member: 61' "http://localhost:$port_a/test-support/coupon-events/$event_close_race/applications")
wait "$close_tx_pid"
check "8 request racing a not-yet-committed close still gets rejected once close commits (409)" \
  "$([[ "$http_code" == "409" ]] && echo 0 || echo 1)"
check "8 no application row is created for the rejected racing member" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_close_race AND member_id=61")" == "0" ]] && echo 0 || echo 1)"

# 9. 종료 후 재신청: 기존 신청자는 종료 후에도 기존 접수번호·상태를 그대로 돌려받고, 신청한 적
#    없는 회원은(각 앱이 종료를 한 번 발견한 뒤부터는) 접수 큐/잠금 없이 즉시 ENDED로 응답하며
#    원장에 어떤 행도 만들지 않는다.
event_ended=$(create_event ended-repeat "UTC_TIMESTAMP(6)" | tail -1)
existing_before_close=$(post "$port_a" "$event_ended" 62)
existing_id=$(printf '%s' "$existing_before_close" | request_id)
close_event "$event_ended"
# 두 앱 모두 아직 이 행사의 종료를 발견한 적이 없다 - 각 앱에 한 번씩 워밍업을 보내 캐시를 채운다.
warm_up_closure_discovery "$port_a" "$event_ended" 6601
warm_up_closure_discovery "$port_b" "$event_ended" 6602
existing_after_close=$(post "$port_b" "$event_ended" 62)
existing_after_id=$(printf '%s' "$existing_after_close" | request_id)
existing_after_status=$(printf '%s' "$existing_after_close" | status_field)
if require_field "9 pre-existing member still gets a requestId after close" "$existing_after_id"; then
  check "9 pre-existing member's requestId is unchanged after close" \
    "$([[ "$existing_after_id" == "$existing_id" ]] && echo 0 || echo 1)"
fi
check "9 pre-existing member's status after close is not ENDED (real ledger status)" \
  "$([[ "$existing_after_status" != "ENDED" ]] && echo 0 || echo 1)"

new_after_close=$(post "$port_a" "$event_ended" 63)
new_after_status=$(printf '%s' "$new_after_close" | status_field)
new_after_id=$(printf '%s' "$new_after_close" | request_id)
check "9 member with no prior application gets ENDED after close" \
  "$([[ "$new_after_status" == "ENDED" ]] && echo 0 || echo 1)"
check "9 ENDED response has a null requestId" \
  "$([[ "$new_after_id" == "null" ]] && echo 0 || echo 1)"
check "9 ENDED response creates no application row" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_ended AND member_id=63")" == "0" ]] && echo 0 || echo 1)"

# 두 앱에서 동시에 신규 회원이 종료 후 신청해도 둘 다 ENDED로 응답하고 행을 만들지 않는다(빠른
# 경로가 큐를 거치지 않으므로 동시 요청끼리 서로 영향을 주지 않는다).
post "$port_a" "$event_ended" 64 >"$log_dir/ended-concurrent-a.json" & pid_a=$!
post "$port_b" "$event_ended" 65 >"$log_dir/ended-concurrent-b.json" & pid_b=$!
wait "$pid_a" "$pid_b"
concurrent_a_status=$(status_field < "$log_dir/ended-concurrent-a.json")
concurrent_b_status=$(status_field < "$log_dir/ended-concurrent-b.json")
check "9 concurrent post-close requests from both apps both get ENDED" \
  "$([[ "$concurrent_a_status" == "ENDED" && "$concurrent_b_status" == "ENDED" ]] && echo 0 || echo 1)"
check "9 concurrent post-close requests create no application rows" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_ended AND member_id IN (64,65)")" == "0" ]] && echo 0 || echo 1)"

# 10. 커밋 후 응답 유실: 신청이 커밋된 뒤 클라이언트가 응답을 못 받고, 그 사이 행사가 종료되고,
#     다른 앱으로 재요청해도 ENDED가 아니라 이미 커밋된 접수를 그대로 돌려받는다.
event_lost_then_closed=$(create_event lost-then-closed "UTC_TIMESTAMP(6)" | tail -1)
post "$port_a" "$event_lost_then_closed" 71 >/dev/null
stored_before_close=$(mysql_exec "SELECT public_request_id FROM coupon_application WHERE event_id=$event_lost_then_closed AND member_id=71")
close_event "$event_lost_then_closed"
retried_after_close=$(post "$port_b" "$event_lost_then_closed" 71)
retried_id=$(printf '%s' "$retried_after_close" | request_id)
retried_status=$(printf '%s' "$retried_after_close" | status_field)
if require_field "10 retry after lost response + since-closed event still has a requestId" "$retried_id" \
  && require_field "10 DB has the originally committed requestId" "$stored_before_close"; then
  check "10 retry returns the committed requestId, not a new one" \
    "$([[ "$retried_id" == "$stored_before_close" ]] && echo 0 || echo 1)"
fi
check "10 retry status is not ENDED (existing application takes priority over the fast path)" \
  "$([[ "$retried_status" != "ENDED" ]] && echo 0 || echo 1)"
check "10 no second row was created for the same member" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_lost_then_closed AND member_id=71")" == "1" ]] && echo 0 || echo 1)"

# 11. 순서를 제어한 경쟁 조건: "기존 신청 조회에서 없음 -> 다른 앱이 같은 회원의 접수를 커밋 ->
#     행사 종료 및 캐시 반영 -> 앞선 요청이 ENDED 반환"을 막는지 확인한다. CouponAdmissionService
#     가 캐시부터 확인하고 기존 접수 조회를 그다음에 하도록 순서를 고정했으므로(순서를 반대로
#     하면 이 정합성이 깨진다), 아래 순서로 실행해도 회원 81의 기존 접수가 항상 이긴다:
#       (a) 회원 81의 접수가 아직 없음을 확인(GET .../me)
#       (b) 앱 B로 회원 81의 접수를 커밋(행사는 아직 OPEN)
#       (c) 행사 종료
#       (d) 앱 A의 캐시를 "다른 회원"(82)으로 워밍업 - 회원 81의 요청과 무관하게 캐시가 채워진다
#       (e) 이제 앱 A로 회원 81이 다시 신청 -> ENDED가 아니라 (b)에서 커밋된 접수를 그대로 받아야 한다
event_race=$(create_event closure-race-existing "UTC_TIMESTAMP(6)" | tail -1)
no_application_before=$(curl --silent -H 'X-Coupon-Admission-Test-Member: 81' \
  "http://localhost:$port_a/test-support/coupon-events/$event_race/applications/me" | status_field)
check "11 (a) member 81 has no application yet (status is CHECKING)" \
  "$([[ "$no_application_before" == "CHECKING" ]] && echo 0 || echo 1)"

committed_via_app_b=$(post "$port_b" "$event_race" 81)
committed_id=$(printf '%s' "$committed_via_app_b" | request_id)
require_field "11 (b) app B commits member 81's application while the event is OPEN" "$committed_id" || true

close_event "$event_race"
warm_up_closure_discovery "$port_a" "$event_race" 82

reapplied_via_app_a=$(post "$port_a" "$event_race" 81)
reapplied_id=$(printf '%s' "$reapplied_via_app_a" | request_id)
reapplied_status=$(printf '%s' "$reapplied_via_app_a" | status_field)
if require_field "11 (e) app A's response has a non-null requestId" "$reapplied_id"; then
  check "11 (e) app A returns member 81's already-committed requestId, not a new one" \
    "$([[ "$reapplied_id" == "$committed_id" ]] && echo 0 || echo 1)"
fi
check "11 (e) app A's response is not ENDED (existing application must win over the closure cache)" \
  "$([[ "$reapplied_status" != "ENDED" ]] && echo 0 || echo 1)"
check "11 only one application row exists for member 81 (no duplicate from the retry)" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_race AND member_id=81")" == "1" ]] && echo 0 || echo 1)"

if [[ "$fail_count" -eq 0 ]]; then
  echo "PASS: all admission validations (1-11) completed against two Spring Boot processes and one MySQL database."
else
  echo "FAIL: $fail_count admission validation(s) failed." >&2
fi
echo "Evidence: $log_dir"
exit "$fail_count"
