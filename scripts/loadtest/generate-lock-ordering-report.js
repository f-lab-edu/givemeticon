#!/usr/bin/env node
// "DB 커넥션을 얻기 전에 Redis 분산 락을 획득하면 DB 락 대기·커넥션 점유가 줄고 고객
// 응답시간도 개선되는가?"를 비교하기 위한 전용 보고서 생성기. reports/mysql-coupon-loadtest/의
// MySQL 기준선 보고서(generate-report.js)와는 완전히 분리된 별도 산출물이다 - 그 보고서는
// "Redis 분산 락은 이번 비교 대상에 추가하지 않는다"는 원래 요구에 따라 redis 비교 run을
// 항상 제외하도록 만들어져 있고, 이 스크립트는 정반대로 그 비교만을 위해 존재한다.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const runRoot = path.join(root, 'reports/mysql-coupon-loadtest/runs'); // 오케스트레이션 스크립트 경로를 그대로 재사용
const reportDir = path.join(root, 'reports/redis-lock-ordering');
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
const consistency = (file) => {
  const out = { statuses: {} };
  for (const line of read(file).trim().split('\n')) {
    const p = line.split('\t');
    if (p[0] === 'stock') Object.assign(out, {stockId:p[1], total:p[2], remain:p[3], decremented:p[4]});
    if (p[0] === 'coupon_count') out.coupons = p[1];
    if (p[0] === 'duplicate_coupon_users') out.duplicates = p[1];
    if (p[0] === 'request_status') out.statuses[p[1]] = p[2];
    if (p[0] === 'unresolved_pending') out.pending = p[1];
  }
  return out;
};

// --- Prometheus 텍스트 포맷에서 마지막(=누적치가 가장 큰) 스냅샷 값을 뽑는다 ---
function promValue(text, name, labelMatch) {
  const re = new RegExp(`^${name}\\{([^}]*)\\}\\s+([0-9eE+\\-.]+)`, 'm');
  let best = null;
  for (const line of text.split('\n')) {
    if (!line.startsWith(name + '{')) continue;
    const m = line.match(new RegExp(`^${name}\\{([^}]*)\\}\\s+([0-9eE+\\-.]+)`));
    if (!m) continue;
    if (labelMatch && !labelMatch(m[1])) continue;
    const v = Number(m[2]);
    if (Number.isFinite(v)) best = (best == null) ? v : best; // 같은 파일 안엔 한 번만 나온다
  }
  return best;
}
function latestPromPerApp(dir) {
  if (!fs.existsSync(dir)) return {};
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.prom')).sort();
  const out = {};
  for (const f of files) {
    const app = f.split('-')[0];
    out[app] = f; // 정렬돼 있으므로 마지막에 덮어써진 값이 최신 스냅샷
  }
  return Object.fromEntries(Object.entries(out).map(([app, f]) => [app, read(path.join(dir, f))]));
}
function redisLockStats(dir) {
  const perApp = latestPromPerApp(dir);
  let acqSum = 0, acqCount = 0, holdSum = 0, holdCount = 0, rejectedCount = 0, hasData = false;
  for (const text of Object.values(perApp)) {
    const a = promValue(text, 'coupon_redis_lock_acquire_seconds_sum', l => l.includes('outcome="acquired"'));
    const ac = promValue(text, 'coupon_redis_lock_acquire_seconds_count', l => l.includes('outcome="acquired"'));
    const r = promValue(text, 'coupon_redis_lock_acquire_seconds_count', l => l.includes('outcome="rejected"'));
    const hs = promValue(text, 'coupon_redis_lock_hold_seconds_sum', () => true);
    const hc = promValue(text, 'coupon_redis_lock_hold_seconds_count', () => true);
    if (a != null) { acqSum += a; hasData = true; }
    if (ac != null) acqCount += ac;
    if (r != null) rejectedCount += r;
    if (hs != null) holdSum += hs;
    if (hc != null) holdCount += hc;
  }
  if (!hasData) return null;
  return {
    acquireAvgMs: acqCount ? (acqSum / acqCount) * 1000 : null,
    holdAvgMs: holdCount ? (holdSum / holdCount) * 1000 : null,
    acquiredCount: acqCount,
    lockAcquireTimeoutCount: rejectedCount, // waitTime(5s) 안에 획득 실패
  };
}
function blockingSqlSamples(dir) {
  if (!fs.existsSync(dir)) return { files: 0, nonEmpty: 0, sample: null };
  const files = fs.readdirSync(dir).filter(f => f.startsWith('mysql-lock-details-'));
  let nonEmpty = 0, sample = null;
  for (const f of files) {
    const body = read(path.join(dir, f)).trim();
    if (body) {
      nonEmpty++;
      if (!sample) sample = body.split('\n')[0];
    }
  }
  return { files: files.length, nonEmpty, sample };
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
  return {
    name, e, c, h, l,
    lockMode: e.lock_mode || 'mysql-only',
    target: number(e.issue_rate), pool: number((e.hikari_max || '').match(/\d+/)?.[0]),
    actual: metric(m, 'iterations'), dropped: metric(m, 'dropped_iterations'),
    issued: metric(m, 'coupon_issue_issued'), rejected: metric(m, 'coupon_issue_rejected'),
    p50: metric(m, 'http_req_duration', 'med'), p95: metric(m, 'http_req_duration', 'p(95)'), p99: metric(m, 'http_req_duration', 'p(99)'),
    under2: metric(m, 'coupon_issue_business_response_under_2s', 'value'),
    iterAvgMs: metric(m, 'iteration_duration', 'avg'), iterP95Ms: metric(m, 'iteration_duration', 'p(95)'),
    peakVus, configuredMaxVUs, vuUtilization,
    maxPending: maxima(h, 'pending'), maxActive: maxima(h, 'active'),
    maxLocks: maxima(l, 'lock_wait_rows'),
    lockStart: l[0] && Number(l[0].innodb_lock_waits), lockEnd: l.at(-1) && Number(l.at(-1).innodb_lock_waits),
    redis: redisLockStats(path.join(dir, 'prometheus')),
    blocking: blockingSqlSamples(dir),
  };
}

const allRuns = fs.existsSync(runRoot) ? fs.readdirSync(runRoot) : [];
// 이 실험 전용으로 만든 run만 다룬다("lockorder-" 접두어). 기존 MySQL 기준선 보고서가 쓰는
// run(rate-*, baseline-*)과 원본 redis-compare-* 탐색 실행은 여기서 건드리지 않는다.
const runs = allRuns.filter(n => n.startsWith('lockorder-')).map(loadRun).filter(Boolean).sort((a, b) => a.name.localeCompare(b.name));
const valid = runs.filter(r => r.c.total && r.c.coupons !== undefined && r.issued != null && Number(r.c.coupons) === r.issued);
const invalid = runs.filter(r => !valid.includes(r));

const ms = v => v == null ? '미측정' : `${v.toFixed(1)} ms`;
const pct = v => v == null ? '미측정' : `${(v * 100).toFixed(1)}%`;

// rate별로 mysql-only/redis-before-db 쌍을 만든다.
const byRate = {};
for (const r of valid) {
  byRate[r.target] = byRate[r.target] || {};
  byRate[r.target][r.lockMode] = r;
}
const rates = Object.keys(byRate).map(Number).sort((a, b) => a - b);

function pairRowsHtml() {
  return rates.map(rate => {
    const mysql = byRate[rate]['mysql-only'];
    const redis = byRate[rate]['redis-before-db'];
    const rowFor = (r, label) => {
      if (!r) return `<tr><td>${label}</td><td colspan="9">실행 없음</td></tr>`;
      const throughput = (r.issued != null && r.e.duration) ? (r.issued / (parseFloat(r.e.duration) || 10)).toFixed(1) : '미측정';
      const dbLockDelta = (r.lockEnd != null && r.lockStart != null) ? (r.lockEnd - r.lockStart) : null;
      const redisCell = r.redis
        ? `획득 ${ms(r.redis.acquireAvgMs)} · 보유 ${ms(r.redis.holdAvgMs)} · 타임아웃 ${r.redis.lockAcquireTimeoutCount ?? 0}건`
        : '해당 없음(MySQL만)';
      const vuCell = r.configuredMaxVUs
        ? `${r.peakVus}/${r.configuredMaxVUs} (${pct(r.vuUtilization)})${r.vuUtilization > 0.9 ? ' ⚠︎VU 상한 근접' : ''}`
        : '미측정';
      return `<tr>
        <td>${esc(label)}<br><code>${esc(r.name)}</code></td>
        <td>${ms(r.p50)} / ${ms(r.p95)} / ${ms(r.p99)}</td>
        <td>${pct(r.under2)}</td>
        <td>${throughput}/s (${r.issued}/${r.rejected})</td>
        <td>${r.actual ?? '미측정'} / ${r.dropped ?? 0}</td>
        <td>${vuCell}</td>
        <td>${ms(r.iterAvgMs)} / ${ms(r.iterP95Ms)}</td>
        <td>${r.maxActive}/${r.pool}, pending 최대 ${r.maxPending}</td>
        <td>${r.maxLocks} 표본, 누적 ${dbLockDelta ?? '?'} 증가</td>
        <td>${redisCell}</td>
        <td>${r.blocking.nonEmpty}/${r.blocking.files} 표본에 차단 SQL 있음${r.blocking.sample ? `<br><code>${esc(r.blocking.sample.slice(0, 80))}...</code>` : ''}</td>
      </tr>`;
    };
    return `<tr><th colspan="11">목표 ${rate} req/s (풀 ${mysql?.pool ?? redis?.pool ?? '?'} 고정)</th></tr>${rowFor(mysql, 'MySQL만')}${rowFor(redis, 'Redis 락 → DB')}`;
  }).join('');
}

function verdictFor(rate) {
  const mysql = byRate[rate]['mysql-only'];
  const redis = byRate[rate]['redis-before-db'];
  if (!mysql || !redis) return '<p>이 요청률은 두 조건 중 한쪽 실행이 없어 비교할 수 없다.</p>';
  const dbDeltaMysql = (mysql.lockEnd != null && mysql.lockStart != null) ? mysql.lockEnd - mysql.lockStart : null;
  const dbDeltaRedis = (redis.lockEnd != null && redis.lockStart != null) ? redis.lockEnd - redis.lockStart : null;
  const dbImproved = (dbDeltaMysql != null && dbDeltaRedis != null) ? dbDeltaRedis < dbDeltaMysql : null;
  const respImproved = (mysql.p95 != null && redis.p95 != null) ? redis.p95 < mysql.p95 : null;
  const throughputMysql = mysql.issued;
  const throughputRedis = redis.issued;
  const throughputImproved = (throughputMysql != null && throughputRedis != null) ? throughputRedis >= throughputMysql : null;
  const redisOverhead = redis.redis ? redis.redis.acquireAvgMs : null;
  let verdict;
  if (dbImproved === false || dbImproved == null) {
    verdict = `DB 행 락 대기 누적치가 MySQL만(${dbDeltaMysql ?? '?'})보다 Redis 락 추가(${dbDeltaRedis ?? '?'})에서 줄지 않았다.`;
  } else if (respImproved && throughputImproved) {
    verdict = `DB 락 대기가 줄었고(${dbDeltaMysql}→${dbDeltaRedis}), HTTP p95(${ms(mysql.p95)}→${ms(redis.p95)})와 실제 발급 처리량(${throughputMysql}→${throughputRedis}건)도 함께 개선됐다.`;
  } else {
    verdict = `DB 락 대기는 줄었지만(${dbDeltaMysql}→${dbDeltaRedis}), 전체 응답시간(p95 ${ms(mysql.p95)}→${ms(redis.p95)})은 ${respImproved ? '개선' : '개선되지 않았다'} - Redis 락 획득 평균 대기 ${ms(redisOverhead)}가 DB 대기 감소분을 상쇄했을 가능성이 있다. DB 대기가 Redis 대기로 자리만 옮겼다면 고객이 기다리는 총 시간은 그대로일 수 있다.`;
  }
  return `<p class="fact${respImproved === false || dbImproved === false ? ' warn' : ''}"><strong>${rate} req/s:</strong> ${verdict}</p>`;
}

const gitCommit = valid[0]?.e.git_commit || outOfScopeCommit();
function outOfScopeCommit() { return '미측정'; }

const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Redis 락 선점 순서 비교 실험 보고서</title><style>
body{font-family:system-ui,-apple-system,sans-serif;line-height:1.5;margin:0;color:#17212b;background:#f6f8fa}main{max-width:1300px;margin:auto;padding:28px}section{background:#fff;padding:22px;margin:16px 0;border-radius:10px;box-shadow:0 1px 3px #0001}h1,h2{margin-top:0}.fact{border-left:5px solid #1769aa;padding:12px;background:#eef6fc;margin:8px 0}.warn{border-left-color:#b54708;background:#fff7ed}table{border-collapse:collapse;width:100%;font-size:12.5px}th,td{border:1px solid #d0d7de;padding:6px;text-align:left;vertical-align:top}th{background:#f6f8fa}code{background:#f1f3f5;padding:1px 4px;font-size:0.95em}@media(max-width:900px){table{display:block;overflow:auto}}</style></head><body><main>
<h1>Redis 분산 락을 DB 커넥션보다 먼저 획득하면 DB 대기·응답시간이 개선되는가</h1>
<p>생성 시각: ${new Date().toISOString()} · 대상 커밋: ${esc(gitCommit)} · 락 적용 순서: Redis 락 획득 → DB 커넥션·트랜잭션 사용(재고 조건부 UPDATE + 유니크 제약 그대로) → 커밋 완료 → 락 해제(DistributedLockAop, AopForTransaction REQUIRES_NEW)</p>
<section><h2>1. 의사결정 요약</h2>${rates.length ? rates.map(verdictFor).join('') : '<p class="warn fact">유효 실행이 없다.</p>'}</section>
<section><h2>2. 실험 조건</h2><ul>
<li>풀 크기는 두 조건에서 동일하게 고정했다(아래 표의 "풀" 값). SQL·재고 조건부 UPDATE·유니크 제약·트랜잭션 경계는 두 조건 동일.</li>
<li>MySQL만: <code>mysql-loadtest</code> 프로필(<code>coupon.distributed-lock.enabled=false</code>) - DistributedLockAop 빈 자체가 생성되지 않아 재고별 동시 쓰기가 조건부 UPDATE/유니크 제약만으로 처리된다.</li>
<li>Redis 락 → DB: <code>mysql-loadtest,redis-lock-loadtest</code> 프로필 - 같은 Facade 호출이 재고별 Redis 락(획득 대기 최대 5s, 보유 최대 3s) 안에서 실행된다.</li>
<li>k6 constant-arrival-rate의 <code>maxVUs</code>를 <code>max(2000, rate*12)</code>로 넉넉히 잡아, dropped_iterations가 k6 설정 자체의 VU 부족이 아니라 서버가 그 요청률을 못 받아내 iteration이 계속 점유돼 있었다는 신호로 해석되게 했다. 아래 표의 "VU 사용" 열이 상한에 근접(⚠︎)하면 이 여유조차 부족했다는 뜻이므로 그 실행의 숫자는 참고만 한다.</li>
</ul></section>
<section><h2>3. 결과 비교 (요청률별 MySQL만 vs Redis 락 → DB)</h2>
<table><thead><tr><th>조건</th><th>HTTP p50/p95/p99</th><th>2초 내 업무응답</th><th>실제 발급 처리량</th><th>실제/누락 iteration</th><th>VU 사용(최대/설정, 사용률)</th><th>iteration 평균/p95</th><th>Hikari active/pool, pending 최대</th><th>DB 락 대기 표본, 누적 증가</th><th>Redis 락 획득/보유/타임아웃</th><th>차단 SQL 표본</th></tr></thead><tbody>${pairRowsHtml() || '<tr><td colspan="11">유효 실행 없음</td></tr>'}</tbody></table>
<p>${invalid.length ? `무효/미완 실행: ${invalid.map(r => `<code>${esc(r.name)}</code>`).join(', ')} - 정합성 불일치 또는 데이터 누락으로 제외했다. 원본은 보존한다.` : '무효 실행 없음.'}</p>
</section>
<section><h2>4. 읽는 법</h2><ul>
<li><strong>DB 락 대기 누적 증가</strong>는 <code>Innodb_row_lock_waits</code>(누적 카운터)의 실행 시작 대비 끝 값 차이다 - 이 실행 동안 실제로 행 락을 기다린 횟수.</li>
<li><strong>Redis 락 획득</strong>은 <code>coupon.redis_lock.acquire</code> 타이머의 outcome=acquired 평균이다 - 락을 실제로 얻기까지 기다린 시간. <strong>보유</strong>는 <code>coupon.redis_lock.hold</code> - 락을 쥔 채로 DB 작업(커밋 포함)에 걸린 시간. <strong>타임아웃</strong>은 waitTime(5s) 안에 획득하지 못해 실패한 건수.</li>
<li>DB 대기가 줄었다고 곧바로 "개선"이라 판단하지 않는다 - Redis 락 획득 대기가 그만큼 늘었다면 고객이 체감하는 총 대기시간(HTTP p95)은 그대로거나 더 나빠질 수 있다. §1의 판단은 DB 대기·HTTP 응답시간·실제 처리량 세 가지를 함께 본다.</li>
<li>VU 사용률이 상한에 근접한 실행은 k6 자체의 VU 여유 부족이 결과에 섞여 있을 수 있으므로 참고용으로만 본다.</li>
</ul></section>
<section><h2>5. 한계</h2><p>요청률별 1회씩만 실행했다(반복 없음 - 변동폭 미측정). 풀 크기 변화와의 조합, 3회 반복, 자원(CPU/메모리/GC) 상세, 오픈 순간 집중 패턴은 미실행이다. 이 실험은 발급 처리(<code>issueCoupon</code>) 단계만 다루며, 접수 단계(현재 비동기로 분리된 <code>acceptOnly</code>)는 애초에 락을 쓰지 않으므로 대상이 아니다.</p>
<p>실행: <code>scripts/loadtest/setup-isolated-db.sh</code> → 두 앱을 원하는 프로필 조합/서로 다른 포트로 기동 → <code>RUN_ID=lockorder-&lt;mysql-only|redis-before-db&gt;-&lt;rate&gt;rps-pool&lt;n&gt;-001 LOCK_MODE=&lt;mysql-only|redis-before-db&gt; STOCK_TOTAL=1000 ISSUE_RATE=&lt;rate&gt; DURATION=10s LOADTEST_HIKARI_MAX=&lt;n&gt; scripts/loadtest/run-arrival-rate.sh</code> → <code>node scripts/loadtest/generate-lock-ordering-report.js</code>.</p></section>
</main></body></html>`;

fs.mkdirSync(reportDir, { recursive: true });
fs.writeFileSync(path.join(reportDir, 'report.html'), html);
console.log(path.join(reportDir, 'report.html'));
