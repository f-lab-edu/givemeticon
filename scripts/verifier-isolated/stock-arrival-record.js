// stock(Redisson+MySQL) 경로용 접수/발급 부하 + 회원별 원본 기록. 기존 scripts/k6/coupon-arrival-rate.js 는 카운터만 남겨
// 회원별 최초 요청 시각/응답 대조가 불가능하다. 이 스크립트는 같은 요청 계약(/internal/loadtest/coupons[/requests])을 보내면서
// 요청마다 한 줄 JSON 을 console.log 한다(--console-output 으로 파일 저장). 운영 코드 무변경.
// 기본 DUPLICATE_RATE=0.10 (ADR-001 주 계약). DUPLICATE_RATE=0 은 고유 요청만 보내는 별도 보조 실험이다.
import http from 'k6/http';
import exec from 'k6/execution';

const targets = (__ENV.TARGETS || 'http://127.0.0.1:18080,http://127.0.0.1:18081').split(',').map((v) => v.trim()).filter(Boolean);
const rate = Number(__ENV.RATE || 1000);
const duration = __ENV.DURATION || '10s';
const stockId = Number(__ENV.STOCK_ID);
const userIdStart = Number(__ENV.USER_ID_START || 900000000);
const mode = __ENV.MODE || 'sync';
// V1(integrated-admission-poll.js)와 같은 workload 계약: 고유 최초 요청 1건/회원 + 결정적 중복 재요청(회원 sequence % round(1/DUPLICATE_RATE) === 0).
// 중복은 같은 회원이 '다른 앱'으로 한 번 더 POST 한다. DUPLICATE_RATE=0 이면 순수 고유 요청(보조 실험).
const duplicateRate = Number(__ENV.DUPLICATE_RATE === undefined ? 0.10 : __ENV.DUPLICATE_RATE);
const windowMs = (() => { const m = /^(\d+)(ms|s|m)$/.exec(duration); if (!m) throw new Error('DURATION must look like 10s'); return Number(m[1]) * ({ ms: 1, s: 1000, m: 60000 })[m[2]]; })();
const path = mode === 'accept' ? '/internal/loadtest/coupons/requests' : '/internal/loadtest/coupons';
if (!Number.isInteger(stockId) || stockId <= 0) throw new Error('STOCK_ID must be a positive integer');

export const options = {
  scenarios: { stock_first_request: {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 2000), maxVUs: Number(__ENV.MAX_VUS || 8000),
    gracefulStop: __ENV.GRACEFUL_STOP || '30s' } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

function classify(r) {
  if (r.status === 200) return 'success';
  if (r.status === 0) {
    const e = String(r.error || '');
    return (/timeout|deadline exceeded/i.test(e) && !/^dial/i.test(e)) ? 'client_timeout' : 'connection_error';
  }
  if (r.status === 400 || r.status === 409) return 'business_rejected';
  if (r.status >= 500) return 'server_error';
  return 'http_error';
}

function post(target, userId) {
  return http.post(`${target}${path}`, JSON.stringify({ stockId, couponName: 'loadtest-coupon', couponType: 'FREE_POINT', price: 1000 }), {
    headers: { 'Content-Type': 'application/json', 'X-Loadtest-User-Id': String(userId), 'X-Run-Id': __ENV.RUN_ID || 'unknown' },
    timeout: __ENV.HTTP_TIMEOUT || '10s' });
}

export default function () {
  const sequence = exec.scenario.iterationInTest;
  const userId = userIdStart + sequence;
  const targetIndex = sequence % targets.length;
  // 실제 전송창: scenario 시작 시각 ~ +DURATION. 창 밖(지연 시작/꼬리)에 나간 요청은 inWindow=false 로 구분해 기록한다.
  const scenarioStartMs = exec.scenario.startTime;
  const sentAtMs = Date.now();
  const r = post(targets[targetIndex], userId);
  console.log(JSON.stringify({ kind: 'stock_request', attempt: 1, userId, target: targetIndex + 1, category: classify(r), status: r.status,
    durationMs: r.timings.duration, sentAtMs, respondedAtMs: Date.now(), scenarioStartMs, inWindow: sentAtMs <= scenarioStartMs + windowMs }));
  if (duplicateRate > 0 && (sequence % Math.round(1 / duplicateRate)) === 0) {
    const dupIndex = (targetIndex + 1) % targets.length;
    const dupSentAtMs = Date.now();
    const d = post(targets[dupIndex], userId);
    console.log(JSON.stringify({ kind: 'stock_request', attempt: 2, userId, target: dupIndex + 1, category: classify(d), status: d.status,
      durationMs: d.timings.duration, sentAtMs: dupSentAtMs, respondedAtMs: Date.now(), scenarioStartMs, inWindow: dupSentAtMs <= scenarioStartMs + windowMs }));
  }
}
