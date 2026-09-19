import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const targetList = (__ENV.TARGETS || 'http://host.docker.internal:8082,http://host.docker.internal:8083')
  .split(',').map((target) => target.trim()).filter(Boolean);
const rate = Number(__ENV.ISSUE_RATE || 100);
const duration = __ENV.DURATION || '10s';
const stockId = Number(__ENV.STOCK_ID);
const userIdStart = Number(__ENV.USER_ID_START || 900000000);

if (!Number.isInteger(stockId) || stockId <= 0) {
  throw new Error('STOCK_ID must be a positive integer');
}

export const issued = new Counter('coupon_issue_issued');
export const rejected = new Counter('coupon_issue_rejected');
export const serverErrors = new Counter('coupon_issue_5xx');
export const timelyBusinessResponse = new Rate('coupon_issue_business_response_under_2s');

export const options = {
  scenarios: {
    unique_member_first_request: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || Math.max(50, rate)),
      maxVUs: Number(__ENV.MAX_VUS || Math.max(200, rate * 2)),
    },
  },
  // 기본 --summary-export는 p90/p95만 담는다. p99까지 표에 채우려면 명시해야 한다.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  // __ITER는 VU마다 다시 0부터 시작한다. 테스트 전체에서 단조 증가하는 번호를 써야
  // 한 iteration = 서로 다른 회원의 최초 신청이라는 실험 계약이 지켜진다.
  const sequence = exec.scenario.iterationInTest;
  const userId = userIdStart + sequence;
  const target = targetList[sequence % targetList.length];
  const response = http.post(`${target}/internal/loadtest/coupons`, JSON.stringify({
    stockId,
    couponName: 'loadtest-coupon',
    couponType: 'FREE_POINT',
    price: 1000,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'X-Loadtest-User-Id': String(userId),
      'X-Run-Id': __ENV.RUN_ID || 'unknown',
    },
    tags: { endpoint: 'coupon_issue', target },
    timeout: __ENV.HTTP_TIMEOUT || '10s',
  });

  const businessResponse = response.status === 200 || response.status === 400 || response.status === 409;
  timelyBusinessResponse.add(businessResponse && response.timings.duration <= 2000);
  if (response.status === 200) issued.add(1);
  else if (response.status >= 400 && response.status < 500) rejected.add(1);
  else if (response.status >= 500) serverErrors.add(1);

  check(response, {
    'business response or no server error': () => businessResponse,
  });
}
