#!/usr/bin/env bash
set -euo pipefail

# 묶음 접수 경로(coupon.admission.batch.enabled=true) 전용 검증이다. validate-two-processes.sh와 같은
# 시나리오 1~7(시작 전 거절, 재시도 멱등, 동일 회원 두 프로세스 동시 신청, 서로 다른 회원 동시 신청,
# 미확정 선행 트랜잭션, 롤백, 응답 유실 재시도)을 배치 경로에도 반복하고, 배치 고유 시나리오
# 8~11(같은 묶음 중복 수렴, 대기열 초과 거절, 실제 다건 INSERT/커밋 검증, 응답 대기 타임아웃 이후에도
# 커밋되는 신청의 CHECKING 응답·후속 조회·재신청 수렴)을 추가한다.
# 기존 givemeticon DB와 단건 경로 검증 DB는 건드리지 않고 전용 DB만 생성/삭제한다.
#
# 정정 이력: 이 스크립트는 처음부터 .data.* 경로를 썼지만, "null == null" 우연한 통과를 막는
# require_field 가드가 없었다(예: 8번 시나리오는 15개 응답이 모두 null이어도 sort -u가 1개로 세어
# 통과할 수 있었다). 이번 버전에서 require_field를 추가하고 11번 시나리오를 새로 넣었다.

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
mysql_container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${COUPON_ADMISSION_BATCH_TEST_DB:-givemeticon_coupon_admission_batch_validation}
port_a=${COUPON_ADMISSION_PORT_A:-18090}
port_b=${COUPON_ADMISSION_PORT_B:-18091}
port_c=${COUPON_ADMISSION_PORT_C:-18092}
log_dir="$repo_dir/build/coupon-admission-batch-validation"
mkdir -p "$log_dir"

if ! docker ps --format '{{.Names}}' | grep -qx "$mysql_container"; then
  echo "MySQL container '$mysql_container' is not running." >&2
  exit 1
fi

mysql_password=$(docker exec "$mysql_container" printenv MYSQL_ROOT_PASSWORD)
app_a_pid=''
app_b_pid=''
app_c_pid=''
app_d_pid=''

cleanup() {
  [[ -n "$app_a_pid" ]] && kill "$app_a_pid" 2>/dev/null || true
  [[ -n "$app_b_pid" ]] && kill "$app_b_pid" 2>/dev/null || true
  [[ -n "$app_c_pid" ]] && kill "$app_c_pid" 2>/dev/null || true
  [[ -n "$app_d_pid" ]] && kill "$app_d_pid" 2>/dev/null || true
}
trap cleanup EXIT

mysql_exec() {
  docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" --batch --skip-column-names -e "$1"
}
mysql_status() {
  docker exec "$mysql_container" mysql -uroot -p"$mysql_password" --batch --skip-column-names \
    -e "SHOW GLOBAL STATUS LIKE '$1';" | awk '{print $2}'
}

docker exec "$mysql_container" mysql -uroot -p"$mysql_password" -e "DROP DATABASE IF EXISTS \`$database\`; CREATE DATABASE \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" < "$repo_dir/src/main/resources/db/migration/V20260920__add_coupon_event_admission_ledger.sql"

start_app() {
  local port=$1 logfile=$2 extra_args=${3:-}
  (
    cd "$repo_dir"
    # shellcheck disable=SC2086
    SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/$database" \
    SPRING_DATASOURCE_USERNAME=root \
    SPRING_DATASOURCE_PASSWORD="$mysql_password" \
    ./gradlew bootRun --args="--server.port=$port --spring.profiles.active=local,coupon-admission,coupon-admission-test,coupon-admission-batch --spring.flyway.enabled=false $extra_args"
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
curl --silent "http://localhost:$port_a/actuator/beans" | grep -q 'couponBatchAdmissionAcceptor' \
  && echo "confirmed: batch acceptor bean registered on app A" \
  || { echo "batch acceptor bean not found on app A - refusing to run batch validation against the single-path bean" >&2; exit 1; }
curl --silent "http://localhost:$port_a/actuator/beans" | grep -q 'couponAdmissionTransactionService' \
  && { echo "unexpected: single-path acceptor bean is also registered while batch.enabled=true" >&2; exit 1; }
echo "confirmed: single-path acceptor bean is not registered while batch.enabled=true"

create_event() {
  local public_id=$1 starts_at=$2
  mysql_exec "INSERT INTO coupon_event (public_id, status, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at) VALUES ('$public_id', 'SCHEDULED', $starts_at, 100000, 50000, 10000, 5000, UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();"
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
# HTTP 200은 "성공"의 필요조건일 뿐이다 - CHECKING도 200으로 온다. 본문을 body_file에 남겨,
# 호출자가 status/requestId까지 확인해 진짜 성공(RESOLVED)인지 CHECKING인지 구분하게 한다.
post_capture() {
  local port=$1 event_id=$2 member_id=$3 body_file=$4
  curl --silent --output "$body_file" --write-out '%{http_code}' -X POST \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications"
}
# 컨트롤러 응답은 전역 CustomResponseBodyReturnValueHandler가 {"message":"SUCCESS","data":{...}}로
# 감싼다. jq 경로를 .data.* 로 맞추지 않으면 항상 null을 비교하게 되어 거짓 통과를 만든다.
request_id() { jq -r '.data.requestId'; }
sequence() { jq -r '.data.acceptanceSequence'; }

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

get_me() {
  local port=$1 event_id=$2 member_id=$3
  curl --silent --show-error --fail \
    -H "X-Coupon-Admission-Test-Member: $member_id" \
    "http://localhost:$port/test-support/coupon-events/$event_id/applications/me"
}
status_field() { jq -r '.data.status'; }

# 1. 시작 전 접수는 원장에 남지 않는다.
prestart_event=$(create_event prestart "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE)" | tail -1)
code=$(post_code "$port_a" "$prestart_event" 1)
check "1 start-before rejects with 409" "$([[ "$code" == "409" ]] && echo 0 || echo 1)"
check "1 start-before leaves no application row" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$prestart_event")" == "0" ]] && echo 0 || echo 1)"

# 2. 같은 회원 재신청은 같은 접수번호를 돌려준다.
event_repeat=$(create_event repeat "UTC_TIMESTAMP(6)" | tail -1)
first=$(post "$port_a" "$event_repeat" 10)
sleep 0.2
retry=$(post "$port_a" "$event_repeat" 10)
first_id=$(printf '%s' "$first" | request_id)
retry_id=$(printf '%s' "$retry" | request_id)
if require_field "2 first response has a non-null requestId" "$first_id" \
  && require_field "2 retry response has a non-null requestId" "$retry_id"; then
  check "2 repeat request returns same requestId" "$([[ "$first_id" == "$retry_id" ]] && echo 0 || echo 1)"
fi
check "2 repeat request creates only one row" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_repeat AND member_id=10")" == "1" ]] && echo 0 || echo 1)"

# 3. 서로 다른 프로세스에서 같은 회원이 동시에 신청해도 한 행만 생긴다("다른 앱의 중복 신청" 수렴).
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

# 4. 서로 다른 회원의 동시 신청은 중복되지 않는 연속 순번을 만든다(다건 배치 유입).
event_distinct=$(create_event distinct-member "UTC_TIMESTAMP(6)" | tail -1)
distinct_pids=()
for member in $(seq 61 90); do
  post "$port_a" "$event_distinct" "$member" >"$log_dir/distinct-$member.json" &
  distinct_pids+=($!)
done
wait "${distinct_pids[@]}"
distinct_stats=$(mysql_exec "SELECT COUNT(*), COUNT(DISTINCT acceptance_sequence), MIN(acceptance_sequence), MAX(acceptance_sequence) FROM coupon_application WHERE event_id=$event_distinct")
check "4 distinct members get 30 unique contiguous sequences" "$([[ "$distinct_stats" == $'30\t30\t1\t30' ]] && echo 0 || echo 1)"

# 5. 미확정 선행 트랜잭션이 행사 행을 잡으면 배치 플러시도 커밋될 때까지 대기한다.
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
  echo "FAIL: 5 batch flush returned before uncommitted first transaction committed" >&2
  fail_count=$((fail_count + 1))
fi
wait "$first_tx_pid" "$second_pid"
check "5 batch flush waits for the row lock and gets sequence 2" "$([[ "$(sequence < "$log_dir/blocked-second.json")" == "2" ]] && echo 0 || echo 1)"

# 6. 묶음 전체 롤백: 여러 서로 다른 신규 회원을 한 앱에 동시에 보내 같은 묶음으로 모이게 하고,
#    insert 강제 실패로 묶음 전체가 커밋 전 상태로 되돌아가는지 확인한다.
event_rollback=$(create_event rollback "UTC_TIMESTAMP(6)" | tail -1)
mysql_exec "CREATE TRIGGER coupon_application_fail_before_insert BEFORE INSERT ON coupon_application FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced admission failure'"
rollback_pids=()
rollback_codes_dir="$log_dir/rollback-codes"
mkdir -p "$rollback_codes_dir"
for member in $(seq 500001 500010); do
  ( post_code "$port_a" "$event_rollback" "$member" > "$rollback_codes_dir/$member" ) &
  rollback_pids+=($!)
done
wait "${rollback_pids[@]}"
rollback_5xx=$(grep -lE '^5' "$rollback_codes_dir"/* | wc -l | tr -d ' ')
mysql_exec "DROP TRIGGER coupon_application_fail_before_insert"
check "6 all 10 requests in the failed batch see an error status" "$([[ "$rollback_5xx" == "10" ]] && echo 0 || echo 1)"
check "6 batch rollback leaves sequence counter at 0" "$([[ "$(mysql_exec "SELECT next_acceptance_sequence FROM coupon_event WHERE id=$event_rollback")" == "0" ]] && echo 0 || echo 1)"
check "6 batch rollback leaves zero application rows" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_rollback")" == "0" ]] && echo 0 || echo 1)"

# 7. 첫 응답을 버린 뒤 재요청해도 기존 접수번호가 반환된다.
event_lost=$(create_event response-lost "UTC_TIMESTAMP(6)" | tail -1)
post "$port_a" "$event_lost" 51 >/dev/null
sleep 0.2
recovered=$(post "$port_b" "$event_lost" 51)
recovered_id=$(printf '%s' "$recovered" | request_id)
stored=$(mysql_exec "SELECT public_request_id FROM coupon_application WHERE event_id=$event_lost AND member_id=51")
if require_field "7 retry response has a non-null requestId" "$recovered_id" \
  && require_field "7 DB has a stored requestId for this member" "$stored"; then
  check "7 retry after lost response returns the committed requestId" "$([[ "$recovered_id" == "$stored" ]] && echo 0 || echo 1)"
fi

# 8. 같은 묶음 내부 중복: 같은 신규 회원을 한 앱에 동시에 여러 번 보내도 한 행·한 순번만 소비한다.
event_same_batch=$(create_event same-batch-duplicate "UTC_TIMESTAMP(6)" | tail -1)
same_batch_pids=()
for i in $(seq 1 15); do
  post "$port_a" "$event_same_batch" 777 >"$log_dir/same-batch-$i.json" &
  same_batch_pids+=($!)
done
wait "${same_batch_pids[@]}"
same_batch_unique_ids=$(for i in $(seq 1 15); do request_id < "$log_dir/same-batch-$i.json"; done | sort -u)
same_batch_ids=$(printf '%s\n' "$same_batch_unique_ids" | wc -l | tr -d ' ')
# sort -u가 "null"만 15번 모아도 고유값 1개로 셀 수 있으므로, 그 하나의 값이 실제 requestId인지도 확인한다.
if require_field "8 the converged value is a non-null requestId" "$same_batch_unique_ids"; then
  check "8 15 concurrent requests for one new member converge to one requestId" "$([[ "$same_batch_ids" == "1" ]] && echo 0 || echo 1)"
fi
check "8 15 concurrent requests for one new member create one row" "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_same_batch AND member_id=777")" == "1" ]] && echo 0 || echo 1)"

# 9. 대기열 용량 초과는 접수 성공으로 안내하지 않는다. 전용 소용량 큐 프로세스를 잠깐 띄워 검증한다.
#    HTTP 200은 CHECKING도 포함하므로, 200 응답의 본문까지 확인해 진짜 RESOLVED만 "성공"으로 센다.
event_queue_full=$(create_event queue-full "UTC_TIMESTAMP(6)" | tail -1)
app_c_pid=$(start_app "$port_c" "$log_dir/app-$port_c.log" "--coupon.admission.batch.queue-capacity=15")
wait_for_app "$port_c" "$log_dir/app-$port_c.log"
queue_full_codes_dir="$log_dir/queue-full-codes"
queue_full_bodies_dir="$log_dir/queue-full-bodies"
mkdir -p "$queue_full_codes_dir" "$queue_full_bodies_dir"
queue_full_pids=()
for member in $(seq 900001 900150); do
  ( post_capture "$port_c" "$event_queue_full" "$member" "$queue_full_bodies_dir/$member.json" \
      > "$queue_full_codes_dir/$member" ) &
  queue_full_pids+=($!)
done
wait "${queue_full_pids[@]}"
kill "$app_c_pid" 2>/dev/null || true
app_c_pid=''
count_resolved=0
count_checking=0
count_503=0
count_other=0
for code_file in "$queue_full_codes_dir"/*; do
  member=$(basename "$code_file")
  http_code=$(cat "$code_file")
  case "$http_code" in
    503) count_503=$((count_503 + 1)) ;;
    200)
      body_status=$(status_field < "$queue_full_bodies_dir/$member.json")
      if [[ "$body_status" == "CHECKING" ]]; then
        count_checking=$((count_checking + 1))
      else
        count_resolved=$((count_resolved + 1))
      fi
      ;;
    *) count_other=$((count_other + 1)) ;;
  esac
done
db_rows_queue_full=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_queue_full")
check "9 at least one request was rejected with 503 under a 15-capacity queue" "$([[ "$count_503" -gt "0" ]] && echo 0 || echo 1)"
check "9 no unexpected HTTP status besides 200/503" "$([[ "$count_other" == "0" ]] && echo 0 || echo 1)"
check "9 resolved + CHECKING + 503 account for all 150 requests" \
  "$([[ "$((count_resolved + count_checking + count_503))" == "150" ]] && echo 0 || echo 1)"
check "9 committed rows equal exactly the resolved responses (no phantom success, CHECKING excluded)" \
  "$([[ "$db_rows_queue_full" == "$count_resolved" ]] && echo 0 || echo 1)"
echo "INFO: scenario 9 sent=150 resolved=$count_resolved checking=$count_checking queue_full=$count_503 other=$count_other"

# 10. 실제 다건 저장 검증: 여러 신규 회원을 한 앱에 동시에 보내고, MySQL 전역 상태의
#     Com_insert/Com_commit 증가량이 요청 수보다 뚜렷하게 적은지 확인한다. 요청마다 INSERT 한 건과
#     COMMIT 한 건이 발생하는 단건 방식이었다면 두 값 모두 요청 수와 거의 같아야 한다.
event_batch_proof=$(create_event batch-insert-proof "UTC_TIMESTAMP(6)" | tail -1)
com_insert_before=$(mysql_status Com_insert)
com_commit_before=$(mysql_status Com_commit)
proof_pids=()
for member in $(seq 800001 800200); do
  post "$port_a" "$event_batch_proof" "$member" >/dev/null &
  proof_pids+=($!)
done
wait "${proof_pids[@]}"
com_insert_after=$(mysql_status Com_insert)
com_commit_after=$(mysql_status Com_commit)
com_insert_delta=$((com_insert_after - com_insert_before))
com_commit_delta=$((com_commit_after - com_commit_before))
proof_rows=$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_batch_proof")
proof_sequences=$(mysql_exec "SELECT COUNT(DISTINCT acceptance_sequence) FROM coupon_application WHERE event_id=$event_batch_proof")
check "10 all 200 distinct new members were admitted" "$([[ "$proof_rows" == "200" && "$proof_sequences" == "200" ]] && echo 0 || echo 1)"
# 느슨한 상한: 단건 방식이면 Com_insert/Com_commit 증가량이 200에 근접한다. 배치가 실제로 동작했다면
# 200/batchSize(=50)에 가까운 수 + 여유(타이밍 분할)만큼만 증가해야 하므로 100(=200의 절반) 미만을 기준으로 삼는다.
check "10 Com_insert increased far fewer times than request count (actual multi-row insert)" \
  "$([[ "$com_insert_delta" -lt "100" ]] && echo 0 || echo 1)"
check "10 Com_commit increased far fewer times than request count (actual batched commit)" \
  "$([[ "$com_commit_delta" -lt "100" ]] && echo 0 || echo 1)"
echo "INFO: scenario 10 requests=200 Com_insert_delta=$com_insert_delta Com_commit_delta=$com_commit_delta (implies ~$((200 / (com_insert_delta > 0 ? com_insert_delta : 1))) rows/insert average)"

# 11. 타임아웃 이후 결과 확인: wait-timeout-millis가 실제 커밋보다 먼저 끝나도 그 신청은 이후
#     커밋된다. 이때 응답은 "접수 실패 확정"이 아니라 CHECKING이어야 하고(성공 위장 금지),
#     후속 조회와 다른 앱으로의 재신청 모두 같은 접수번호로 수렴해야 하며, DB에는 1건·연속 순번만
#     남아야 한다.
event_checking=$(create_event checking-after-timeout "UTC_TIMESTAMP(6)" | tail -1)
port_d=${COUPON_ADMISSION_PORT_D:-18093}
app_d_pid=$(start_app "$port_d" "$log_dir/app-$port_d.log" "--coupon.admission.batch.wait-timeout-millis=1000")
wait_for_app "$port_d" "$log_dir/app-$port_d.log"
(
  docker exec -i "$mysql_container" mysql -uroot -p"$mysql_password" "$database" <<SQL
START TRANSACTION;
SELECT id FROM coupon_event WHERE id=$event_checking FOR UPDATE;
DO SLEEP(3);
COMMIT;
SQL
) >"$log_dir/checking-hold.log" 2>&1 & hold_pid=$!
sleep 0.5
epoch_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
checking_started_ms=$(epoch_ms)
checking_response=$(post "$port_d" "$event_checking" 61)
checking_elapsed_ms=$(($(epoch_ms) - checking_started_ms))
checking_status=$(printf '%s' "$checking_response" | status_field)
checking_request_id=$(printf '%s' "$checking_response" | request_id)
checking_sequence=$(printf '%s' "$checking_response" | sequence)
check "11 timed-out request responds around wait-timeout-millis, not the full 3s hold" \
  "$([[ "$checking_elapsed_ms" -ge 800 && "$checking_elapsed_ms" -lt 2500 ]] && echo 0 || echo 1)"
check "11 timed-out request returns CHECKING, not a success status" \
  "$([[ "$checking_status" == "CHECKING" ]] && echo 0 || echo 1)"
check "11 CHECKING response has no requestId (no fabricated success)" \
  "$([[ -z "$checking_request_id" || "$checking_request_id" == "null" ]] && echo 0 || echo 1)"
check "11 CHECKING response has no acceptanceSequence (no fabricated success)" \
  "$([[ -z "$checking_sequence" || "$checking_sequence" == "null" ]] && echo 0 || echo 1)"
check "11 DB has no committed row yet at the moment of the CHECKING response" \
  "$([[ "$(mysql_exec "SELECT COUNT(*) FROM coupon_application WHERE event_id=$event_checking AND member_id=61")" == "0" ]] && echo 0 || echo 1)"

wait "$hold_pid"
resolved_status="CHECKING"
resolved_response=""
for _ in $(seq 1 25); do
  resolved_response=$(get_me "$port_d" "$event_checking" 61)
  resolved_status=$(printf '%s' "$resolved_response" | status_field)
  [[ "$resolved_status" != "CHECKING" ]] && break
  sleep 0.2
done
check "11 GET /me eventually shows the real committed status, not CHECKING" \
  "$([[ "$resolved_status" != "CHECKING" ]] && echo 0 || echo 1)"
resolved_request_id=$(printf '%s' "$resolved_response" | request_id)
resolved_sequence=$(printf '%s' "$resolved_response" | sequence)
require_field "11 resolved GET /me has a non-null requestId" "$resolved_request_id" || true
require_field "11 resolved GET /me has a non-null acceptanceSequence" "$resolved_sequence" || true

# 다른 앱(포트 A, 기본 wait-timeout)으로 재신청해도 같은 접수번호로 수렴해야 한다.
retry_on_other_app=$(post "$port_a" "$event_checking" 61)
retry_request_id=$(printf '%s' "$retry_on_other_app" | request_id)
if require_field "11 retry-on-other-app response has a non-null requestId" "$retry_request_id" \
  && require_field "11 resolved requestId available for comparison" "$resolved_request_id"; then
  check "11 retry on a different app converges to the same requestId" \
    "$([[ "$retry_request_id" == "$resolved_request_id" ]] && echo 0 || echo 1)"
fi

checking_final_stats=$(mysql_exec "SELECT COUNT(*), COUNT(DISTINCT acceptance_sequence), MIN(acceptance_sequence), MAX(acceptance_sequence) FROM coupon_application WHERE event_id=$event_checking AND member_id=61")
check "11 exactly one row and one sequence exist for this member after everything settles" \
  "$([[ "$checking_final_stats" == $'1\t1\t1\t1' ]] && echo 0 || echo 1)"

kill "$app_d_pid" 2>/dev/null || true
app_d_pid=''

if [[ "$fail_count" -eq 0 ]]; then
  echo "PASS: all coupon batch admission validations completed against two Spring Boot processes and one MySQL database."
else
  echo "FAIL: $fail_count batch admission validation(s) failed." >&2
fi
echo "Evidence: $log_dir"
exit "$fail_count"
