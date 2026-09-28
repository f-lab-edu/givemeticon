#!/usr/bin/env node
// "접수/발급 분리 + 묶음 차감" 구조를 기존 동기 발급(MySQL 기준선)과 같은 조건에서 비교하는
// 전용 보고서 생성기. reports/mysql-coupon-loadtest/의 runs 디렉터리를 그대로 재사용하되,
// 이 실험 전용 run("batch-" 접두어)만 다룬다. 다른 실험(redis-lock-ordering 등)의 run과는
// 서로 건드리지 않는다.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const runRoot = path.join(root, 'reports/mysql-coupon-loadtest/runs');
const reportDir = path.join(root, 'reports/coupon-batch-issuance');
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const read = (file) => fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
const number = (v) => Number.isFinite(Number(v)) ? Number(v) : null;
const metric = (m, key, field = 'count') => m[key] && number(m[key][field]);
const env = (file) => Object.fromEntries(read(file).split('\n').map(line => line.split(/=(.*)/s)).filter(x => x.length > 1));
const csv = (file) => {
  const [head, ...rows] = read(file).trim().split('\n');
  if (!head) return [];
  const cols = head.split(',');
  return rows.filter(Boolean).map(r => Object.fromEntries(r.split(',').map((v, i) => [cols[i], v])));
};

// verify-batch-issuance.sql의 출력을 파싱한다(consistency.txt).
function consistency(file) {
  const out = { statuses: {} };
  for (const line of read(file).trim().split('\n')) {
    const p = line.split('\t');
    if (p[0] === 'stock') Object.assign(out, { stockId: p[1], total: p[2], remain: p[3], decremented: p[4] });
    if (p[0] === 'coupon_count') out.coupons = number(p[1]);
    if (p[0] === 'duplicate_coupon_users') out.duplicates = number(p[1]);
    if (p[0] === 'request_status') out.statuses[p[1]] = number(p[2]);
    if (p[0] === 'unresolved_pending') out.pending = number(p[1]);
    if (p[0] === 'order_evidence') Object.assign(out, { minId: p[1], maxId: p[2], requestCount: number(p[3]) });
    if (p[0] === 'boundary_request_id') out.boundaryId = p[1] === 'NULL' ? null : p[1];
    if (p[0] === 'fcfs_violation_issued_after_boundary') out.fcfsViolationAfter = number(p[1]);
    if (p[0] === 'fcfs_violation_not_issued_within_boundary') out.fcfsViolationWithin = number(p[1]);
    if (p[0] === 'last_issued_at') out.lastIssuedAt = p[1] === 'NULL' ? null : p[1];
    if (p[0] === 'last_sold_out_at') out.lastSoldOutAt = p[1] === 'NULL' ? null : p[1];
    if (p[0] === 'db_utc_now') out.dbUtcNow = p[1];
  }
  return out;
}

// run_id를 재사용해 같은 디렉터리에서 재시도한 이력이 있으면(이 실험 준비 중 실제로 있었다)
// prometheus/ 아래에 이전 시도의 스냅샷이 그대로 남아있다. sinceUtcIso(이 run의 started_at_utc)
// 이전 타임스탬프 파일은 건너뛰고, 스크레이프 실패로 빈 채 남은 스냅샷도 건너뛴다 - 그래야
// "마지막 파일"이 실제로는 이전 시도의 잔재이거나 빈 스크레이프인 경우를 피한다.
function latestPromPerApp(dir, sinceUtcIso) {
  if (!fs.existsSync(dir)) return {};
  const sinceMs = sinceUtcIso ? Date.parse(sinceUtcIso) : 0;
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.prom')).sort();
  const out = {};
  for (const f of files) {
    const m = f.match(/^(app\d+)-(.+)\.prom$/);
    if (!m) continue;
    const ts = Date.parse(m[2]);
    if (sinceMs && Number.isFinite(ts) && ts < sinceMs) continue;
    const content = read(path.join(dir, f));
    if (!content.trim()) continue;
    out[m[1]] = content; // 정렬돼 있으므로 마지막에 남는 값이 이 run 안에서 가장 최신인 유효 스냅샷
  }
  return out;
}
function sumPromValues(text, name, labelMatch) {
  let sum = 0, found = false;
  const re = new RegExp(`^${name}\\{([^}]*)\\}\\s+([0-9eE+\\-.]+)`);
  for (const line of text.split('\n')) {
    if (!line.startsWith(name + '{')) continue;
    const m = line.match(re);
    if (!m) continue;
    if (labelMatch && !labelMatch(m[1])) continue;
    const v = Number(m[2]);
    if (Number.isFinite(v)) { sum += v; found = true; }
  }
  return found ? sum : null;
}
// coupon.issue.duration(outcome=issued/sold_out/rejected, path=batch/immediate) 히스토그램 버킷을
// 두 앱 스냅샷에서 합산해 p95를 선형보간으로 근사한다. Micrometer 기본 버킷 경계는 코드가
// 같은 두 인스턴스에서 동일하므로 le별로 그대로 더할 수 있다.
function mergedHistogramPercentile(dir, name, labelMatch, p, sinceUtcIso) {
  const perApp = latestPromPerApp(dir, sinceUtcIso);
  const totals = new Map();
  const re = new RegExp(`^${name}_bucket\\{([^}]*)\\}\\s+([0-9eE+\\-.]+)`);
  for (const text of Object.values(perApp)) {
    for (const line of text.split('\n')) {
      if (!line.startsWith(name + '_bucket{')) continue;
      const m = line.match(re);
      if (!m) continue;
      if (labelMatch && !labelMatch(m[1])) continue;
      const leMatch = m[1].match(/le="([^"]+)"/);
      if (!leMatch) continue;
      const le = leMatch[1] === '+Inf' ? Infinity : Number(leMatch[1]);
      totals.set(le, (totals.get(le) || 0) + Number(m[2]));
    }
  }
  if (!totals.size) return null;
  const buckets = [...totals.entries()].map(([le, count]) => ({ le, count })).sort((a, b) => a.le - b.le);
  const total = buckets.at(-1).count;
  if (!total) return null;
  const target = total * p;
  let prevLe = 0, prevCount = 0;
  for (const b of buckets) {
    if (b.count >= target) {
      if (b.le === Infinity) return prevLe * 1000;
      const frac = (b.count === prevCount) ? 0 : (target - prevCount) / (b.count - prevCount);
      return (prevLe + frac * (b.le - prevLe)) * 1000;
    }
    prevLe = b.le; prevCount = b.count;
  }
  return null;
}
function issueDurationStats(dir, pathTag, sinceUtcIso) {
  const perApp = latestPromPerApp(path.join(dir, 'prometheus'), sinceUtcIso);
  let sum = 0, count = 0, hasData = false;
  for (const text of Object.values(perApp)) {
    const s = sumPromValues(text, 'coupon_issue_duration_seconds_sum', l => l.includes(`path="${pathTag}"`));
    const c = sumPromValues(text, 'coupon_issue_duration_seconds_count', l => l.includes(`path="${pathTag}"`));
    if (s != null) { sum += s; hasData = true; }
    if (c != null) count += c;
  }
  if (!hasData) return null;
  const p95Ms = mergedHistogramPercentile(path.join(dir, 'prometheus'), 'coupon_issue_duration_seconds', l => l.includes(`path="${pathTag}"`), 0.95, sinceUtcIso);
  return { avgMs: count ? (sum / count) * 1000 : null, count, p95Ms };
}

// run.env(UTC)와 consistency.txt(KST, DB 세션 타임존)의 시각을 같은 순간의 db_utc_now로 보정해 뺀다.
// consistency.txt에서 같은 SELECT로 함께 찍은
// (updated_date 계열의 마지막 시각, db_utc_now)를 이용해 오프셋(시간)을 구하고, run.env의
// started_at_utc(UTC)로부터 지난 초를 계산한다.
function offsetHoursOf(dbLocalTs, dbUtcNowTs) {
  if (!dbLocalTs || !dbUtcNowTs) return null;
  const local = Date.parse(dbLocalTs.replace(' ', 'T') + 'Z');
  const utcNow = Date.parse(dbUtcNowTs.replace(' ', 'T') + 'Z');
  if (Number.isNaN(local) || Number.isNaN(utcNow)) return null;
  return Math.round((local - utcNow) / 3600000); // 정수 시간 오프셋(예: KST=+9)
}
function secondsSinceStart(startedAtUtcIso, localTs, offsetHours) {
  if (!startedAtUtcIso || !localTs || offsetHours == null) return null;
  const started = Date.parse(startedAtUtcIso.trim().replace(' ', 'T') + (startedAtUtcIso.trim().endsWith('Z') ? '' : 'Z'));
  const localAsUtc = Date.parse(localTs.replace(' ', 'T') + 'Z');
  if (Number.isNaN(started) || Number.isNaN(localAsUtc)) return null;
  const actualUtc = localAsUtc - offsetHours * 3600000;
  return (actualUtc - started) / 1000;
}

function blockingSqlSamples(dir) {
  const lockDir = dir;
  if (!fs.existsSync(lockDir)) return { files: 0, nonEmpty: 0 };
  const files = fs.readdirSync(lockDir).filter(f => f.startsWith('mysql-lock-details-'));
  let nonEmpty = 0;
  for (const f of files) if (read(path.join(lockDir, f)).trim()) nonEmpty++;
  return { files: files.length, nonEmpty };
}

function loadRun(name) {
  const dir = path.join(runRoot, name);
  const summaryFile = path.join(dir, 'k6-summary.json');
  if (!fs.existsSync(summaryFile)) return null;
  const m = JSON.parse(read(summaryFile)).metrics;
  const e = env(path.join(dir, 'run.env'));
  const c = consistency(path.join(dir, 'consistency.txt'));
  const h = csv(path.join(dir, 'hikari.csv'));
  const l = csv(path.join(dir, 'mysql-locks.csv'));
  const maxima = (rows, field) => Math.max(0, ...rows.map(r => Number(r[field]) || 0));
  const vus = m.vus, vusMax = m.vus_max;
  const configuredMaxVUs = vusMax ? number(vusMax.max) : null;
  const peakVus = vus ? number(vus.max) : null;
  const vuUtilization = (peakVus != null && configuredMaxVUs) ? peakVus / configuredMaxVUs : null;

  const workerMode = e.worker_mode || 'off';
  const batchSize = number(e.batch_size);
  const target = number(e.issue_rate);
  const duration = parseFloat(e.duration) || 10;
  const actual = metric(m, 'iterations');
  const dropped = metric(m, 'dropped_iterations');
  const droppedRatio = (actual != null && dropped != null && (actual + dropped) > 0) ? dropped / (actual + dropped) : null;

  const offsetHours = offsetHoursOf(c.lastIssuedAt || c.lastSoldOutAt, c.dbUtcNow);
  const lastIssuedSec = secondsSinceStart(e.started_at_utc, c.lastIssuedAt, offsetHours);
  const lastSoldOutSec = secondsSinceStart(e.started_at_utc, c.lastSoldOutAt, offsetHours);
  const completionSec = [lastIssuedSec, lastSoldOutSec].filter(v => v != null).reduce((a, b) => Math.max(a, b), 0) || null;

  const pathTag = workerMode === 'batch' ? 'batch' : (workerMode === 'single' ? 'async' : 'immediate');
  // 동기 경로(workerMode=off)는 접수+발급이 같은 HTTP 요청 안에서 끝나므로 "접수→확정"이
  // 곧 HTTP 응답시간이다 - k6가 이미 안정적으로 측정한 값을 그대로 쓴다(스크레이프 실패로
  // 비어있을 수 있는 Prometheus 히스토그램에 기대지 않는다). 묶음/단건 비동기는 접수 응답이
  // 확정 시점을 대변하지 못하므로 Prometheus 타이머가 유일한 소스다.
  const issueDuration = workerMode === 'off'
    ? { avgMs: null, p95Ms: metric(m, 'http_req_duration', 'p(95)'), count: actual }
    : issueDurationStats(dir, pathTag, e.started_at_utc);

  return {
    name, e, c, h, l, dir,
    workerMode, batchSize, target, duration,
    actual, dropped, droppedRatio,
    missedTarget: droppedRatio != null ? droppedRatio > 0.01 : null,
    p95: metric(m, 'http_req_duration', 'p(95)'), p50: metric(m, 'http_req_duration', 'med'), p99: metric(m, 'http_req_duration', 'p(99)'),
    under2: metric(m, 'coupon_issue_business_response_under_2s', 'value'),
    peakVus, configuredMaxVUs, vuUtilization,
    maxActive: maxima(h, 'active'), pool: number((e.hikari_max || '').match(/\d+/)?.[0]),
    lockStart: l[0] && Number(l[0].innodb_lock_waits), lockEnd: l.at(-1) && Number(l.at(-1).innodb_lock_waits),
    blocking: blockingSqlSamples(dir),
    completionSec, issueDuration,
  };
}

const allRuns = fs.existsSync(runRoot) ? fs.readdirSync(runRoot) : [];
const runs = allRuns.filter(n => n.startsWith('batch-')).map(loadRun).filter(Boolean).sort((a, b) => a.name.localeCompare(b.name));
const valid = runs.filter(r => r.c.total != null && r.c.coupons != null);
const invalid = runs.filter(r => !valid.includes(r));

const ms = v => v == null ? '미측정' : `${v.toFixed(0)} ms`;
const sec = v => v == null ? '미측정' : `${v.toFixed(1)} s`;
const pct = v => v == null ? '미측정' : `${(v * 100).toFixed(1)}%`;
const mean = (arr) => arr.length ? arr.reduce((a, b) => a + b, 0) / arr.length : null;
const range = (arr) => arr.length ? `${Math.min(...arr).toFixed(0)}–${Math.max(...arr).toFixed(0)}` : '-';

// (workerMode/batchSize, rate)로 묶는다. sync는 batchSize=null.
function groupKey(r) { return `${r.workerMode === 'batch' ? `n${r.batchSize}` : 'sync'}@${r.target}`; }
const groups = {};
for (const r of valid) { (groups[groupKey(r)] ||= []).push(r); }
const configs = [...new Set(valid.map(r => r.workerMode === 'batch' ? `n${r.batchSize}` : 'sync'))]
  .sort((a, b) => a === 'sync' ? -1 : b === 'sync' ? 1 : Number(a.slice(1)) - Number(b.slice(1)));
const rates = [...new Set(valid.map(r => r.target))].sort((a, b) => a - b);

function summaryRow(config, rate) {
  const rs = groups[`${config}@${rate}`] || [];
  if (!rs.length) return `<tr><td>${esc(config)}</td><td>${rate}</td><td colspan="7">실행 없음</td></tr>`;
  const anyMissed = rs.some(r => r.missedTarget);
  const p95s = rs.map(r => r.p95).filter(v => v != null);
  const under2s = rs.map(r => r.under2).filter(v => v != null);
  const completions = rs.map(r => r.completionSec).filter(v => v != null);
  const reqP95s = rs.map(r => r.issueDuration?.p95Ms).filter(v => v != null);
  const fcfsBad = rs.some(r => (r.c.fcfsViolationAfter || 0) > 0 || (r.c.fcfsViolationWithin || 0) > 0);
  const pendingBad = rs.some(r => (r.c.pending || 0) > 0);
  return `<tr>
    <td>${esc(config)}</td><td>${rate}</td>
    <td>${rs.length}회${anyMissed ? ' <span class="warn-inline">⚠︎도착률 미달 포함</span>' : ''}</td>
    <td>${ms(mean(p95s))} (${range(p95s)})</td>
    <td>${pct(mean(under2s))}</td>
    <td>${sec(mean(completions))} (${completions.length ? range(completions.map(v=>v)).replace(/(\d+)/g,(x)=>x) : '-'}${completions.length?'s':''})</td>
    <td>${ms(mean(reqP95s))}</td>
    <td class="${fcfsBad ? 'warn-inline' : ''}">${fcfsBad ? '위반 있음' : '위반 0'}</td>
    <td class="${pendingBad ? 'warn-inline' : ''}">${pendingBad ? 'PENDING 잔존' : '0'}</td>
  </tr>`;
}

function runDetailRows() {
  return valid.map(r => `<tr>
    <td><code>${esc(r.name)}</code></td>
    <td>${r.actual ?? '-'} / ${r.dropped ?? 0} (${pct(r.droppedRatio)})${r.missedTarget ? ' ⚠︎' : ''}</td>
    <td>${r.peakVus ?? '-'}/${r.configuredMaxVUs ?? '-'} (${pct(r.vuUtilization)})</td>
    <td>${ms(r.p50)} / ${ms(r.p95)} / ${ms(r.p99)}</td>
    <td>${pct(r.under2)}</td>
    <td>${sec(r.completionSec)}</td>
    <td>${r.issueDuration ? `${ms(r.issueDuration.avgMs)} / ${ms(r.issueDuration.p95Ms)} (n=${r.issueDuration.count})` : '미측정'}</td>
    <td>${r.c.coupons ?? '-'}/${r.c.total ?? '-'}, 중복 ${r.c.duplicates ?? '-'}, 잔여 ${r.c.remain ?? '-'}, PENDING ${r.c.pending ?? '-'}</td>
    <td>위반(경계이후 ISSUED) ${r.c.fcfsViolationAfter ?? '-'} / (경계이내 미발급) ${r.c.fcfsViolationWithin ?? '-'}</td>
  </tr>`).join('');
}

function verdictHtml() {
  const lines = rates.map(rate => {
    const sync = groups[`sync@${rate}`] || [];
    if (!sync.length) return `<p class="fact warn">${rate} req/s: 동기 기준선 실행이 없어 비교할 수 없다.</p>`;
    const syncCompletion = mean(sync.map(r => r.completionSec).filter(v => v != null));
    const syncP95 = mean(sync.map(r => r.p95).filter(v => v != null));
    const batchConfigs = configs.filter(c => c !== 'sync');
    const cells = batchConfigs.map(cfg => {
      const rs = groups[`${cfg}@${rate}`] || [];
      if (!rs.length) return `${cfg}: 실행 없음`;
      const completion = mean(rs.map(r => r.completionSec).filter(v => v != null));
      const acceptP95 = mean(rs.map(r => r.p95).filter(v => v != null));
      const fcfsBad = rs.some(r => (r.c.fcfsViolationAfter || 0) > 0 || (r.c.fcfsViolationWithin || 0) > 0);
      const better = (completion != null && syncCompletion != null) ? completion <= syncCompletion : null;
      return `${cfg}: 최종 완료 ${sec(completion)}(기준선 ${sec(syncCompletion)} 대비 ${better == null ? '비교불가' : better ? '이내' : '초과'}), 접수 p95 ${ms(acceptP95)}(기준선 ${ms(syncP95)})${fcfsBad ? ' ⚠︎선착순 위반' : ''}`;
    });
    return `<p class="fact"><strong>${rate} req/s:</strong> ${cells.join(' · ')}</p>`;
  });
  return lines.join('');
}

const gitCommit = valid[0]?.e.git_commit || '미측정';

const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>접수/발급 분리 + 묶음 차감 비교 실험 보고서</title><style>
body{font-family:system-ui,-apple-system,sans-serif;line-height:1.5;margin:0;color:#17212b;background:#f6f8fa}main{max-width:1300px;margin:auto;padding:28px}section{background:#fff;padding:22px;margin:16px 0;border-radius:10px;box-shadow:0 1px 3px #0001}h1,h2{margin-top:0}.fact{border-left:5px solid #1769aa;padding:12px;background:#eef6fc;margin:8px 0}.warn{border-left-color:#b54708;background:#fff7ed}.warn-inline{color:#b54708;font-weight:600}table{border-collapse:collapse;width:100%;font-size:12.5px}th,td{border:1px solid #d0d7de;padding:6px;text-align:left;vertical-align:top}th{background:#f6f8fa}code{background:#f1f3f5;padding:1px 4px;font-size:0.95em}@media(max-width:900px){table{display:block;overflow:auto}}</style></head><body><main>
<h1>쿠폰 발급을 "접수/발급 분리 + 묶음 차감"으로 바꾸면 기존 동기 발급 대비 어떤가</h1>
<p>생성 시각: ${new Date().toISOString()} · 대상 커밋: ${esc(gitCommit)} · 접수: <code>POST /internal/loadtest/coupons/requests</code>(원장 INSERT만) · 발급: <code>CouponBatchIssueWorker</code>가 접수번호 오름차순 N건을 한 트랜잭션에서 재고 N차감 → 쿠폰 다건 INSERT → 신청 다건 ISSUED, 부족분은 SOLD_OUT 처리</p>
<section><h2>1. 의사결정 요약</h2>${rates.length ? verdictHtml() : '<p class="warn fact">유효 실행이 없다.</p>'}</section>
<section><h2>2. 실험 조건</h2><ul>
<li>두 Spring JVM, Hikari max=${valid[0]?.pool ?? 10}/앱(고정), 단일 MySQL, 재고 1,000, k6 constant-arrival-rate 10초 - run마다 새 coupon_stock 행을 쓴다.</li>
<li>동기 기준선: <code>coupon.issue-worker.mode=off</code>(두 비동기 워커 모두 끔) + k6가 <code>POST /internal/loadtest/coupons</code>(동기 발급)를 호출.</li>
<li>접수/발급 분리 + 묶음 차감: <code>coupon.issue-worker.mode=batch</code>, <code>coupon.batch-issue.size=N</code>(50/100/200) + k6가 <code>POST /internal/loadtest/coupons/requests</code>(접수만)를 호출, 실제 발급은 CouponBatchIssueWorker가 별도 처리.</li>
<li>도착률 500·1,000 req/s × 묶음 크기 50·100·200 × 각 3회 반복, k6 maxVUs는 도착률의 12배 이상으로 VU 부족이 dropped_iterations의 원인이 되지 않게 했다.</li>
</ul></section>
<section><h2>3. 조합별 요약(각 3회 반복 평균)</h2>
<table><thead><tr><th>구성</th><th>도착률</th><th>반복</th><th>접수 HTTP p95(평균, 범위)</th><th>2초 내 접수 비율(평균)</th><th>최종 발급 완료(평균, 범위)</th><th>신청별 접수→확정 p95(평균)</th><th>선착순 위반</th><th>PENDING 잔존</th></tr></thead><tbody>
${configs.flatMap(cfg => rates.map(rate => summaryRow(cfg, rate))).join('')}
</tbody></table></section>
<section><h2>4. 개별 실행 상세 및 정합성 검증</h2>
<table><thead><tr><th>run</th><th>실제/누락 iteration(비율)</th><th>VU 사용(최대/설정)</th><th>접수 HTTP p50/p95/p99</th><th>2초 내 비율</th><th>최종 발급 완료</th><th>신청별 접수→확정 avg/p95</th><th>정합성(발급/재고, 중복, 잔여, PENDING)</th><th>선착순 위반(경계이후 ISSUED / 경계이내 미발급)</th></tr></thead><tbody>
${runDetailRows() || '<tr><td colspan="9">유효 실행 없음</td></tr>'}
</tbody></table>
<p>${invalid.length ? `무효/미완 실행: ${invalid.map(r => `<code>${esc(r.name)}</code>`).join(', ')} - 데이터 누락으로 표에서 제외했다. 원본은 보존한다.` : '무효 실행 없음.'}</p>
</section>
<section><h2>5. 정합성 검증 SQL</h2><p><code>scripts/loadtest/verify-batch-issuance.sql</code>을 각 run 종료 직후 <code>consistency.txt</code>로 저장한다. 핵심 확인 항목:</p>
<ul>
<li>발급 수 = 재고 총량, 회원별 중복 0, 재고 잔여 0, 처리 후 PENDING 0.</li>
<li>선착순 위반 0: "접수번호 기준 앞 stock_total건"(boundary_request_id 이하)은 전부 ISSUED여야 하고, 그 밖은 ISSUED면 안 된다 - 두 방향 모두 COUNT가 0이어야 위반이 없다.</li>
</ul>
<pre style="white-space:pre-wrap;background:#f6f8fa;padding:12px;border-radius:6px;font-size:12px;overflow:auto">${esc(read(path.join(root, 'scripts/loadtest/verify-batch-issuance.sql')))}</pre>
</section>
<section><h2>6. 읽는 법</h2><ul>
<li><strong>최종 발급 완료</strong>는 run.env의 <code>started_at_utc</code>(부하 시작, UTC)부터 <code>coupon_issue_request.updated_date</code>가 ISSUED/SOLD_OUT으로 바뀐 마지막 시각까지다. updated_date는 DB 세션 타임존(KST)이라, 같은 SELECT에서 함께 찍은 <code>UTC_TIMESTAMP(6)</code>로 오프셋을 보정해 뺐다.</li>
<li><strong>신청별 접수→확정 p95</strong>는 <code>coupon.issue.duration</code>(outcome=issued/sold_out, path=batch 또는 immediate) Micrometer 히스토그램을 두 앱에서 합산해 버킷 선형보간으로 근사한 값이다 - 정확한 percentile이 아니라 근사치임을 밝힌다.</li>
<li><strong>도착률 미달(⚠︎)</strong>은 dropped_iterations 비율이 1%를 넘는 실행이다 - 그 실행의 수치는 "목표 도착률에서 실제로 관찰된 결과"가 아니라 "서버가 그 요청률을 못 받아낸 상태에서의 결과"로 읽어야 한다.</li>
</ul></section>
<section><h2>7. 한계</h2><p>실제 발급 워커의 재고별 리더십은 "고정 리더 유지"가 아니라 매 배치 시도마다 waitTime=0 락으로 그 순간의 소유권을 가리는 방식이다(CreateCouponFacade#processBatchForStock 주석 참고) - 장시간 한쪽 JVM만 계속 이기는지, 두 JVM이 번갈아 처리하는지는 이 보고서에서 별도로 측정하지 않았다. 재시도·상태 조회(GET /requests/{id}) 자체의 부하는 이 실험 범위 밖이다.</p>
<p>재현: <code>scripts/loadtest/setup-isolated-db.sh</code> → 두 앱을 해당 profile/env로 기동 → <code>RUN_ID=batch-&lt;sync|n&lt;N&gt;&gt;-&lt;rate&gt;rps-&lt;rep&gt; MODE=&lt;sync|accept&gt; VERIFY_SQL=scripts/loadtest/verify-batch-issuance.sql STOCK_TOTAL=1000 ISSUE_RATE=&lt;rate&gt; DURATION=10s scripts/loadtest/run-arrival-rate.sh</code> → <code>node scripts/loadtest/generate-batch-issuance-report.js</code>.</p></section>
</main></body></html>`;

fs.mkdirSync(reportDir, { recursive: true });
fs.writeFileSync(path.join(reportDir, 'report.html'), html);
console.log(path.join(reportDir, 'report.html'));
console.log(`runs found: ${runs.length}, valid: ${valid.length}, invalid: ${invalid.length}`);
