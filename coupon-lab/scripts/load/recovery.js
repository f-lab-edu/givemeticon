// Test D load: a fixed-rate stream against POST /coupon-events/{eventId}/issue while scripts/verify.sh recovery injects a fault.
// Started by `scripts/verify.sh recovery`:
//   k6 run -e BASE_URL=... -e EVENT_ID=7 -e START_AT_MS=<epoch ms> --out json=... --summary-export ... recovery.js
// The layout (rates, VUs, request timeout) is the "recovery" block of profiles.json, which recovery_postprocess.py reads as well.
//
// Two constant-arrival-rate scenarios: `warmup` (10 s @ 100 rps), then `main` (60 s @ 500 rps). setup() holds the whole
// test until START_AT_MS, so the runner knows the wall-clock second at which `main` starts and schedules the fault from it.
// Every request is tagged phase=warmup|main and sec=<whole seconds since the start of `main`> (warmup: -10..-1), counted by
// the second it STARTED in. Outcomes (recovery_results, tag result): issued (200), sold_out (409), http_5xx, timeout (status 0:
// timeout or network error), other. The seq of every 200 goes into the recovery_seq trend.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

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
const MAIN_MEMBER_BASE = 1000000; // warmup members are 1.., main members start here: no member is ever sent twice

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
};

http.setResponseCallback(http.expectedStatuses(200, 409));

const results = new Counter('recovery_results');
const seqs = new Trend('recovery_seq');
const URL = `${BASE_URL}/coupon-events/${EVENT_ID}/issue`;
const HEADERS = { 'Content-Type': 'application/json' };

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
  // iterationInTest is unique within a scenario, so every request of a scenario uses a different member.
  const memberId = (phase === 'main' ? MAIN_MEMBER_BASE : 0) + exec.scenario.iterationInTest + 1;

  const res = http.post(URL, JSON.stringify({ memberId }), { headers: HEADERS, tags, timeout: TIMEOUT });

  let result = 'other';
  if (res.status === 200) {
    result = 'issued';
  } else if (res.status === 409) {
    result = 'sold_out';
  } else if (res.status >= 500) {
    result = 'http_5xx';
  } else if (res.status === 0) {
    result = 'timeout';
  }
  results.add(1, Object.assign({}, tags, { result }));
  if (result === 'issued') {
    try {
      const seq = res.json('seq');
      if (typeof seq === 'number') {
        seqs.add(seq, tags);
      }
    } catch (e) {
      // a 200 without a readable seq is still counted as issued above
    }
  }
}
