import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

// 접수(고유 회원 1만 명, 10초) + 중복 재신청 10%(다른 앱으로) + 접수 결과가 CHECKING/PENDING인
// 회원은 최종 결과(ISSUED/SOLD_OUT)까지 약 1초 간격(무작위 편차 포함)으로 조회한다.
// 재시도(중복 재신청)는 "최초 유효 신청" 시각을 갱신하지 않는다 - firstAppliedAt은 항상 그 회원의
// 첫 POST 직전 시각으로 고정한다.
const targets = (__ENV.TARGETS || 'http://127.0.0.1:18080,http://127.0.0.1:18081')
  .split(',').map((v) => v.trim()).filter(Boolean);
const rate = Number(__ENV.RATE || 1000);
const duration = __ENV.DURATION || '10s';
const eventId = Number(__ENV.EVENT_ID);
const memberIdStart = Number(__ENV.MEMBER_ID_START || 700000000);
const duplicateRate = Number(__ENV.DUPLICATE_RATE || 0.10);
const pollBaseMs = Number(__ENV.POLL_BASE_MS || 1000);
const pollJitterMs = Number(__ENV.POLL_JITTER_MS || 300);
const pollBudgetMs = Number(__ENV.POLL_BUDGET_MS || 180000);

if (!Number.isInteger(eventId) || eventId <= 0) {
  throw new Error('EVENT_ID must be a positive integer');
}

export const admissionAttempts = new Counter('admission_attempts');
export const admissionChecking = new Counter('admission_checking');
export const admissionConnectionErrors = new Counter('admission_connection_errors');
export const admissionClientTimeouts = new Counter('admission_client_timeouts');
export const admissionHttpErrors = new Counter('admission_http_errors');
export const admissionEventClosed = new Counter('admission_event_closed');
export const admissionResponseValidationFailures = new Counter('admission_response_validation_failures');
export const admissionSuccess = new Counter('admission_success');
export const admissionSuccessUnder2s = new Counter('admission_success_under_2_seconds');
export const duplicateAttempts = new Counter('duplicate_attempts');

export const finalIssued = new Counter('final_issued');
export const finalSoldOut = new Counter('final_sold_out');
export const finalTimeout = new Counter('final_timeout');
export const finalPollError = new Counter('final_poll_error');
export const timeToFinal = new Trend('time_to_final_ms');
export const pollRequests = new Counter('poll_requests');

export const options = {
  scenarios: {
    admission_and_poll: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      // 2026-09-27 진단으로 낮췄다(docs/latency/03-generator-normalization.md) - 실측 동시 VU
      // 피크가 5,241이었다. 부족해지면(dropped_iterations>0으로 나타난다) 다시 올린다.
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 6500),
      maxVUs: Number(__ENV.MAX_VUS || 8000),
      // 접수 자체는 duration(기본 10s) 안에서 도착하지만, 각 반복(=회원 1명)은 그 뒤로도 최대
      // POLL_BUDGET_MS(기본 180s)까지 조회를 계속한다 - gracefulStop이 그 시간을 보장해야 한다.
      gracefulStop: __ENV.GRACEFUL_STOP || '200s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

function post(target, memberId) {
  return http.post(
    `${target}/test-support/coupon-events/${eventId}/applications`,
    null,
    {
      headers: { 'X-Coupon-Admission-Test-Member': String(memberId) },
      timeout: __ENV.HTTP_TIMEOUT || '10s',
    },
  );
}

function get(target, memberId) {
  return http.get(
    `${target}/test-support/coupon-events/${eventId}/applications/me`,
    {
      headers: { 'X-Coupon-Admission-Test-Member': String(memberId) },
      timeout: __ENV.HTTP_TIMEOUT || '10s',
    },
  );
}

function parseBody(response) {
  if (response.status !== 200) return null;
  try {
    const parsed = JSON.parse(response.body);
    return parsed && parsed.data;
  } catch (e) {
    return null;
  }
}

function classify(response, data) {
  const isChecking = data != null && data.status === 'CHECKING';
  // ENDED는 종료된 행사에 대한 빠른 확정 응답이다(HTTP 200, requestId=null) - 잠금/큐를 거치는
  // 기존 409(CouponEventNotOpenException) 거절과 같은 "정상 종료 거절"로 집계한다. 두 경로 모두
  // "이 회원은 이 행사에 접수한 적이 없고 행사가 끝났다"는 같은 최종 결과이며, 폴링 대상도 아니다.
  const isEnded = data != null && data.status === 'ENDED';
  const resolvedSuccess = data != null && !isChecking && !isEnded && Boolean(data.requestId);
  if (resolvedSuccess) return 'success';
  if (isChecking) return 'checking';
  if (isEnded) return 'event_closed';
  if (response.status === 409) return 'event_closed';
  if (response.status === 0) {
    const errorText = String(response.error || '');
    if (/timeout|deadline exceeded/i.test(errorText) && !/^dial/i.test(errorText)) return 'client_timeout';
    return 'connection_error';
  }
  if (response.status >= 400) return 'http_error';
  return 'response_validation_failure';
}

export default function () {
  const sequence = exec.scenario.iterationInTest;
  const memberId = memberIdStart + sequence;
  const primaryTargetIndex = sequence % targets.length;
  const primaryTarget = targets[primaryTargetIndex];
  const isDuplicateCandidate = duplicateRate > 0 && (sequence % Math.round(1 / duplicateRate)) === 0;

  // sentAtMs(요청 직전)와 respondedAtMs(응답 직후)를 분리해서 남긴다 - durationMs(k6가 계산한
  // 왕복 시간)만으로는 "그 요청이 실제로 언제 발생했는지"(초당 최초 신청 분포)를 정확히 재구성할
  // 수 없다. 로그 줄의 time= 필드(초 단위)보다 더 정밀한 밀리초 단위 원본이 필요해서 추가했다.
  const firstAppliedAtMs = Date.now();
  const sentAtMs = firstAppliedAtMs;
  const firstResponse = post(primaryTarget, memberId);
  const respondedAtMs = Date.now();
  const firstData = parseBody(firstResponse);
  const category = classify(firstResponse, firstData);

  admissionAttempts.add(1);
  if (category === 'success') {
    admissionSuccess.add(1);
    if (firstResponse.timings.duration <= 2000) admissionSuccessUnder2s.add(1);
  }
  if (category === 'checking') admissionChecking.add(1);
  if (category === 'connection_error') admissionConnectionErrors.add(1);
  if (category === 'client_timeout') admissionClientTimeouts.add(1);
  if (category === 'http_error') admissionHttpErrors.add(1);
  if (category === 'event_closed') admissionEventClosed.add(1);
  if (category === 'response_validation_failure') admissionResponseValidationFailures.add(1);

  console.log(JSON.stringify({
    kind: 'admission', memberId, target: primaryTargetIndex + 1, category,
    requestId: firstData ? firstData.requestId : null, status: firstResponse.status,
    durationMs: firstResponse.timings.duration, sentAtMs, respondedAtMs,
  }));

  let duplicateRequestId = null;
  let duplicateCategory = null;
  if (isDuplicateCandidate) {
    // 같은 회원이 다른 앱으로 재신청한다 - firstAppliedAtMs는 그대로 두고(측정 시작 갱신 없음),
    // 새 순번을 만들지 않고 같은 requestId를 돌려주는지만 별도로 기록한다.
    duplicateAttempts.add(1);
    const otherTargetIndex = (primaryTargetIndex + 1) % targets.length;
    const otherTarget = targets[otherTargetIndex];
    const dupResponse = post(otherTarget, memberId);
    const dupData = parseBody(dupResponse);
    duplicateCategory = classify(dupResponse, dupData);
    duplicateRequestId = dupData ? dupData.requestId : null;
    console.log(JSON.stringify({
      kind: 'duplicate', memberId, target: otherTargetIndex + 1, category: duplicateCategory,
      requestId: duplicateRequestId, primaryRequestId: firstData ? firstData.requestId : null,
      status: dupResponse.status, durationMs: dupResponse.timings.duration,
    }));
  }

  // 접수 자체가 서버에 도달하지 못했으면(연결 오류·클라이언트 타임아웃) 조회할 신청이 없다 -
  // 조회 단계로 넘어가지 않고 그대로 종료한다(이 회원은 "확정 실패"로 남는다).
  if (category === 'connection_error' || category === 'client_timeout' || category === 'event_closed'
      || category === 'response_validation_failure') {
    check(firstResponse, { 'admission reached the server': () => false });
    return;
  }

  const pollTarget = targets[primaryTargetIndex];
  const deadlineMs = firstAppliedAtMs + pollBudgetMs;
  let finalStatus = null;
  let pollCount = 0;
  let pollErrored = false;
  while (Date.now() < deadlineMs) {
    const jitter = (Math.random() * 2 - 1) * pollJitterMs;
    const sleepMs = Math.max(50, pollBaseMs + jitter);
    sleep(sleepMs / 1000); // k6 sleep()은 초 단위
    const pollResponse = get(pollTarget, memberId);
    pollRequests.add(1);
    pollCount += 1;
    const pollData = parseBody(pollResponse);
    if (pollData == null) {
      pollErrored = true;
      continue;
    }
    if (pollData.status === 'ISSUED' || pollData.status === 'SOLD_OUT') {
      finalStatus = pollData.status;
      break;
    }
  }

  const elapsedMs = Date.now() - firstAppliedAtMs;
  if (finalStatus === 'ISSUED') {
    finalIssued.add(1);
    timeToFinal.add(elapsedMs);
  } else if (finalStatus === 'SOLD_OUT') {
    finalSoldOut.add(1);
    timeToFinal.add(elapsedMs);
  } else if (pollErrored && finalStatus == null) {
    finalPollError.add(1);
  } else {
    finalTimeout.add(1);
  }

  console.log(JSON.stringify({
    kind: 'final', memberId, finalStatus: finalStatus || (pollErrored ? 'POLL_ERROR' : 'TIMEOUT'),
    elapsedMs, pollCount, withinBudget: elapsedMs <= pollBudgetMs && finalStatus != null,
    admissionRequestId: firstData ? firstData.requestId : null,
    duplicateRequestId, duplicateCategory,
  }));
}
