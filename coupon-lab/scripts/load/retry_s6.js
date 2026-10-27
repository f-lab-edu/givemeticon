// Test F follow-up for stage s6: the clients whose request was not confirmed (503 or no answer) retry it later with the SAME
// requestId and member. Started by `scripts/verify.sh recovery --stage s6 --scenario broker-down --client-retry` after the
// load ended and the queue settled:
//   k6 run -e BASE_URL=... -e EVENT_ID=7 -e RETRY_FILE=<json [{"rid": ..., "member": ...}]> --out json=... retry_s6.js
// One attempt per request (50 VUs, shared iterations). Outcomes (retry_results, tags result and rid): accepted (202),
// unconfirmed (503), http_5xx, timeout, other. recovery_s6_postprocess.py compares them with coupon_request before and after.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const EVENT_ID = __ENV.EVENT_ID;
const RETRIES = JSON.parse(open(__ENV.RETRY_FILE));
if (!/^[0-9]+$/.test(String(EVENT_ID))) {
  throw new Error('EVENT_ID (an integer) is required');
}

export const options = {
  scenarios: {
    retry: { executor: 'shared-iterations', vus: 50, iterations: Math.max(1, RETRIES.length), maxDuration: '10m' },
  },
  systemTags: ['status', 'method', 'scenario'],
  discardResponseBodies: true,
};

http.setResponseCallback(http.expectedStatuses(202));

const results = new Counter('retry_results');
const URL = `${BASE_URL}/coupon-events/${EVENT_ID}/issue`;
const HEADERS = { 'Content-Type': 'application/json' };

export default function () {
  const index = exec.scenario.iterationInTest;
  if (index >= RETRIES.length) {
    return;
  }
  const { rid, member } = RETRIES[index];
  const res = http.post(URL, JSON.stringify({ memberId: Number(member), requestId: rid }), { headers: HEADERS, timeout: '5s' });
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
  results.add(1, { result, rid });
}
