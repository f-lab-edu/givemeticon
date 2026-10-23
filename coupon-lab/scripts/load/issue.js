// Test B load scenarios against POST /coupon-events/{eventId}/issue. Started by `scripts/verify.sh load`:
//   k6 run -e BASE_URL=... -e EVENT_ID=... -e PROFILE=ramping|spike --out json=... --summary-export ... issue.js
// The timeline and stock live in profiles.json, which postprocess.py reads as well.
//
// One ramping-arrival-rate scenario: a warmup, then for every step a short ramp to its rate followed by a hold.
// Each request is tagged by the point on that timeline where it starts:
//   phase=warmup | transition | measure, target_rps=<rate of the step>
// Only phase=measure is aggregated. 200 (issued) and 409 (sold out) are both successful responses.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const LAYOUT = JSON.parse(open('./profiles.json'));
const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const EVENT_ID = __ENV.EVENT_ID;
const PROFILE = __ENV.PROFILE;

const profile = LAYOUT.profiles[PROFILE];
if (!profile) {
  throw new Error(`PROFILE must be one of ${Object.keys(LAYOUT.profiles).join('|')}, got '${PROFILE}'`);
}
if (!EVENT_ID) {
  throw new Error('EVENT_ID is required');
}

const WARMUP = LAYOUT.warmup;
const TRANSITION_S = LAYOUT.transition_seconds;

const stages = [{ target: WARMUP.rps, duration: `${WARMUP.seconds}s` }];
const timeline = [{ until: WARMUP.seconds, phase: 'warmup', rps: WARMUP.rps }];
let elapsed = WARMUP.seconds;
for (const step of profile.steps) {
  stages.push({ target: step.rps, duration: `${TRANSITION_S}s` });
  stages.push({ target: step.rps, duration: `${step.seconds - TRANSITION_S}s` });
  timeline.push({ until: elapsed + TRANSITION_S, phase: 'transition', rps: step.rps });
  timeline.push({ until: elapsed + step.seconds, phase: 'measure', rps: step.rps });
  elapsed += step.seconds;
}

function windowAt(seconds) {
  for (const w of timeline) {
    if (seconds < w.until) {
      return w;
    }
  }
  return timeline[timeline.length - 1];
}

export const options = {
  scenarios: {
    [PROFILE]: {
      executor: 'ramping-arrival-rate',
      startRate: WARMUP.rps,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 3000,
      stages,
    },
  },
  // Keep the raw output small: custom tags (phase, target_rps, result) are all that is needed besides the status.
  systemTags: ['status', 'method', 'scenario'],
  discardResponseBodies: true,
};

http.setResponseCallback(http.expectedStatuses(200, 409));

const results = new Counter('coupon_results');
const URL = `${BASE_URL}/coupon-events/${EVENT_ID}/issue`;
const HEADERS = { 'Content-Type': 'application/json' };

export default function () {
  const w = windowAt((Date.now() - exec.scenario.startTime) / 1000);
  const tags = { phase: w.phase, target_rps: String(w.rps) };
  // iterationInTest is unique across all VUs of the run, so every request uses a different member.
  const memberId = exec.scenario.iterationInTest + 1;

  const res = http.post(URL, JSON.stringify({ memberId }), { headers: HEADERS, tags });

  const result = res.status === 200 ? 'issued' : res.status === 409 ? 'sold_out' : 'error';
  results.add(1, Object.assign({}, tags, { result }));
}
