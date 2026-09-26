import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const targets = (__ENV.TARGETS || 'http://127.0.0.1:18080,http://127.0.0.1:18081')
  .split(',').map((value) => value.trim()).filter(Boolean);
const rate = Number(__ENV.RATE || 1000);
const duration = __ENV.DURATION || '10s';
const eventId = Number(__ENV.EVENT_ID);
const memberIdStart = Number(__ENV.MEMBER_ID_START || 700000000);

if (!Number.isInteger(eventId) || eventId <= 0) {
  throw new Error('EVENT_ID must be a positive integer');
}

export const admissionSuccess = new Rate('admission_success');
export const admissionWithinTwoSeconds = new Rate('admission_success_under_2_seconds');
export const admissionServerErrors = new Counter('admission_server_errors');
export const admissionTimeouts = new Counter('admission_timeouts');
export const admissionAttempts = new Counter('admission_attempts');
// 묶음 경로는 응답 대기 타임아웃 시 HTTP 200으로 CHECKING을 반환한다("접수 실패 확정"이 아니라
// "아직 모름"이라는 뜻). HTTP 200만으로 접수 성공을 집계하면 CHECKING을 성공으로 잘못 센다 - 반드시
// 본문의 status와 requestId까지 확인해야 한다.
export const admissionChecking = new Counter('admission_checking');

export const options = {
  scenarios: {
    unique_member_first_application: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || Math.max(2000, rate * 2)),
      maxVUs: Number(__ENV.MAX_VUS || Math.max(5000, rate * 5)),
      gracefulStop: __ENV.GRACEFUL_STOP || '60s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  // scenario.iterationInTest는 VU 로컬 __ITER와 달리 시나리오 전체에서 단조 증가한다.
  const sequence = exec.scenario.iterationInTest;
  const memberId = memberIdStart + sequence;
  const targetIndex = sequence % targets.length;
  const target = targets[targetIndex];

  const response = http.post(
    `${target}/test-support/coupon-events/${eventId}/applications`,
    null,
    {
      headers: { 'X-Coupon-Admission-Test-Member': String(memberId) },
      tags: { admission_target: `app${targetIndex + 1}` },
      timeout: __ENV.HTTP_TIMEOUT || '10s',
    },
  );

  // HTTP 200은 "성공"의 필요조건일 뿐 충분조건이 아니다 - CHECKING도 200으로 온다. 본문을 확인해야
  // 실제로 순번이 확정된 접수(resolved)인지, 아직 결과를 모르는 CHECKING인지 구분할 수 있다.
  let data = null;
  if (response.status === 200) {
    try {
      const parsed = JSON.parse(response.body);
      data = parsed && parsed.data;
    } catch (e) {
      data = null;
    }
  }
  const isChecking = data != null && data.status === 'CHECKING';
  const resolvedSuccess = data != null && !isChecking && Boolean(data.requestId);
  const timeout = response.error_code === 1050 || response.error_code === 1211;
  admissionAttempts.add(1, { admission_target: `app${targetIndex + 1}` });
  admissionSuccess.add(resolvedSuccess);
  admissionWithinTwoSeconds.add(resolvedSuccess && response.timings.duration <= 2000);
  if (isChecking) admissionChecking.add(1);
  if (response.status >= 500) admissionServerErrors.add(1);
  if (timeout) admissionTimeouts.add(1);

  if (!resolvedSuccess) {
    // 실패·타임아웃·CHECKING 회원은 원본 console 로그에서 회원 ID로 대조할 수 있다(성공 회원별
    // 라벨은 만들지 않는다). bodyStatus로 CHECKING과 진짜 실패를 구분해 남긴다.
    console.error(JSON.stringify({ memberId, target: targetIndex + 1, status: response.status,
      bodyStatus: data ? data.status : null, error: response.error, errorCode: response.error_code,
      durationMs: response.timings.duration }));
  }
  check(response, { 'admission resolves to a definite success (not CHECKING)': () => resolvedSuccess });
}
