// Test D/F load for stage s6: the recovery.js layout (warmup 10 s @ 100 rps, then main 60 s @ 500 rps, START_AT_MS hold),
// against POST /coupon-events/{eventId}/issue, with one difference: every request carries a fresh requestId made here, so a
// request is known by its id whatever the answer was (a timeout included). Started by `scripts/verify.sh recovery --stage s6`:
//   k6 run -e BASE_URL=... -e EVENT_ID=7 -e START_AT_MS=<epoch ms> --out json=... --summary-export ... recovery_s6.js
// Outcomes (recovery_results, tags result and rid): accepted (202), unconfirmed (503: nack, return, confirm timeout or broker
// unreachable; retryable), http_5xx (any other 5xx), timeout (status 0: timeout or network error; also tagged error_code),
// other. recovery_s6_postprocess.py compares the rid sets with the coupon_request rows. Every point also carries the member id
// (tag member), so a later client retry (retry_s6.js) can send the same request again.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const LAYOUT = JSON.parse(open('./profiles.json')).recovery;
const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const EVENT_ID = __ENV.EVENT_ID;
const START_AT_MS = Number(__ENV.START_AT_MS) || 0;
if (!/^[0-9]+$/.test(String(EVENT_ID))) {
  throw new Error('EVENT_ID (an integer) is required');
}

const WARMUP = LAYOUT.warmup;
const LOAD = LAYOUT.load;
const TIMEOUT = `${LAYOUT.http_timeout_seconds}s`;
const MAIN_MEMBER_BASE = 1000000; // as in recovery.js: no member is ever sent twice

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: WARMUP.rps,
      timeUnit: '1s',
      duration: `${WARMUP.seconds}s`,
      preAllocatedVUs: WARMUP.pre_allocated_vus,
      maxVUs: WARMUP.max_vus,
    },
    main: {
      executor: 'constant-arrival-rate',
      rate: LOAD.rps,
      timeUnit: '1s',
      duration: `${LOAD.seconds}s`,
      startTime: `${WARMUP.seconds}s`,
      preAllocatedVUs: LOAD.pre_allocated_vus,
      maxVUs: LOAD.max_vus,
    },
  },
  systemTags: ['status', 'method', 'scenario'],
  discardResponseBodies: true,
};

http.setResponseCallback(http.expectedStatuses(202));

const results = new Counter('recovery_results');
const URL = `${BASE_URL}/coupon-events/${EVENT_ID}/issue`;
const HEADERS = { 'Content-Type': 'application/json' };

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function setup() {
  const wait = START_AT_MS - Date.now();
  if (wait > 0) {
    sleep(wait / 1000);
  }
}

export default function () {
  const phase = exec.scenario.name;
  const sec = Math.floor((Date.now() - exec.scenario.startTime) / 1000) - (phase === 'main' ? 0 : WARMUP.seconds);
  const tags = { phase, sec: String(sec) };
  const memberId = (phase === 'main' ? MAIN_MEMBER_BASE : 0) + exec.scenario.iterationInTest + 1;
  const rid = uuidv4();

  const res = http.post(URL, JSON.stringify({ memberId, requestId: rid }), { headers: HEADERS, tags, timeout: TIMEOUT });

  let result = 'other';
  if (res.status === 202) {
    result = 'accepted';
  } else if (res.status === 503) {
    result = 'unconfirmed';
  } else if (res.status >= 500) {
    result = 'http_5xx';
  } else if (res.status === 0) {
    result = 'timeout';
  }
  const failure = res.status === 0 ? { error_code: String(res.error_code) } : {};
  results.add(1, Object.assign({}, tags, { result, rid, member: String(memberId) }, failure));
}
