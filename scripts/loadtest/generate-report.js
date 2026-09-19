#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const reportDir = path.join(root, 'reports/mysql-coupon-loadtest');
const runRoot = path.join(reportDir, 'runs');
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
function loadRun(name) {
  const dir = path.join(runRoot, name); const summaryFile = path.join(dir, 'k6-summary.json');
  if (!fs.existsSync(summaryFile)) return null;
  const m = JSON.parse(read(summaryFile)).metrics;
  const e = env(path.join(dir, 'run.env')); const c = consistency(path.join(dir, 'consistency.txt'));
  const h = csv(path.join(dir, 'hikari.csv')); const l = csv(path.join(dir, 'mysql-locks.csv'));
  const maxima = (rows, field) => Math.max(0, ...rows.map(r => Number(r[field]) || 0));
  return { name, e, c, h, l,
    target: number(e.issue_rate), actual: metric(m,'iterations'), dropped: metric(m,'dropped_iterations'), pool: number((e.hikari_max || '').match(/\d+/)?.[0]),
    issued: metric(m,'coupon_issue_issued'), rejected: metric(m,'coupon_issue_rejected'),
    p50: metric(m,'http_req_duration','med'), p95: metric(m,'http_req_duration','p(95)'), p99: metric(m,'http_req_duration','p(99)'),
    under2: metric(m,'coupon_issue_business_response_under_2s','value'), failures: metric(m,'http_req_failed','rate'),
    maxPending: maxima(h,'pending'), maxActive: maxima(h,'active'), maxLocks: maxima(l,'lock_wait_rows'),
    lockStart: l[0] && l[0].innodb_lock_waits, lockEnd: l.at(-1) && l.at(-1).innodb_lock_waits,
  };
}
// redis-compare-* run은 "Redis 분산 락은 이번 비교 대상에 추가하지 않는다"는 요구사항에
// 따라 이번 보고서의 비교/차트에서 항상 제외한다. 원본 데이터는 지우지 않고
// reports/mysql-coupon-loadtest/runs/에 그대로 남겨두되(README에 사유를 남김), 이 스크립트가
// 실수로 다시 포함시키지 않도록 lock_mode로 걸러낸다.
const allRuns = fs.existsSync(runRoot) ? fs.readdirSync(runRoot).map(loadRun).filter(Boolean).sort((a,b) => a.name.localeCompare(b.name)) : [];
const isRedisComparison = (r) => (r.e.lock_mode || 'mysql-only') !== 'mysql-only' || /^redis-compare-/.test(r.name);
const outOfScope = allRuns.filter(isRedisComparison);
const runs = allRuns.filter(r => !isRedisComparison(r));
const invalid = runs.filter(r => r.c.total && r.issued != null && Number(r.c.coupons) !== r.issued);
const valid = runs.filter(r => r.c.total && r.c.coupons !== undefined && !invalid.includes(r));
const ms = v => v == null ? '미측정' : `${v.toFixed(1)} ms`;
const pct = v => v == null ? '미측정' : `${(v * 100).toFixed(2)}%`;
const line = (points, width=760, height=160) => {
  if (!points.length) return '<p>표본 없음</p>'; const max=Math.max(1,...points); const dx=width/Math.max(1,points.length-1);
  return `<svg viewBox="0 0 ${width} ${height}" role="img"><path d="M0 ${height-1}H${width}" stroke="#b8c2cc"/><polyline fill="none" stroke="#1769aa" stroke-width="2" points="${points.map((p,i)=>`${i*dx},${height-(p/max)*(height-12)}`).join(' ')}"/><text x="4" y="14">최대 ${max}</text></svg>`;
};
const rateRuns = valid.filter(r => /^rate-/.test(r.name));
const rows = valid.map(r => `<tr><td>${esc(r.name)}</td><td>${r.c.total}</td><td>${r.target}/s</td><td>${r.actual ?? '미측정'}/${r.dropped ?? 0}</td><td>${r.issued}/${r.rejected}</td><td>${ms(r.p50)} / ${ms(r.p95)} / ${ms(r.p99)}</td><td>${pct(r.under2)}</td><td>${r.maxActive}/${r.pool || '?'}, ${r.maxPending}</td><td>${r.maxLocks}, ${r.lockStart ?? '?'}→${r.lockEnd ?? '?'}</td><td>${r.c.coupons}, 중복 ${r.c.duplicates}, 잔여 ${r.c.remain}, PENDING ${r.c.pending}</td></tr>`).join('');
const rateChart = line(rateRuns.map(r => r.p95 || 0));
const evidenceRun = valid.find(r => r.name.includes('1000rps')) || valid.at(-1);
const hikariChart = evidenceRun ? line(evidenceRun.h.map(x=>Number(x.pending)||0)) : '<p>표본 없음</p>';
const lockChart = evidenceRun ? line(evidenceRun.l.map(x=>Number(x.lock_wait_rows)||0)) : '<p>표본 없음</p>';
const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MySQL 쿠폰 동시성 부하 실험 보고서</title><style>
body{font-family:system-ui,-apple-system,sans-serif;line-height:1.5;margin:0;color:#17212b;background:#f6f8fa}main{max-width:1200px;margin:auto;padding:28px}section{background:#fff;padding:22px;margin:16px 0;border-radius:10px;box-shadow:0 1px 3px #0001}h1,h2{margin-top:0}.fact{border-left:5px solid #1769aa;padding:12px;background:#eef6fc}.warn{border-left-color:#b54708;background:#fff7ed}table{border-collapse:collapse;width:100%;font-size:13px}th,td{border:1px solid #d0d7de;padding:7px;text-align:left;vertical-align:top}th{background:#f6f8fa}svg{width:100%;background:#fbfdff;border:1px solid #d0d7de}code{background:#f1f3f5;padding:2px 4px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}@media(max-width:700px){.grid{grid-template-columns:1fr}table{display:block;overflow:auto}}</style></head><body><main>
<h1>MySQL 기반 쿠폰 동시성 제어 부하 실험</h1><p>생성 시각: ${new Date().toISOString()} · 대상 커밋: ${esc(valid[0]?.e.git_commit || '미측정')} · 로컬 단일 PC/두 JVM/단일 Docker MySQL</p>
<section><h2>1. 의사결정 요약</h2><div class="fact"><strong>관측 사실:</strong> 풀 10/앱(설정상 DB 연결 예산 20)에서 100 RPS는 1,001회가 생성되어 1,000장 발급을 완료했다. 500 RPS부터 생성 누락과 2초 목표 미달이 생겼고, 1,000 RPS는 k6 VU 상한에 닿아 목표 유입 자체를 만들지 못했다.</div><p><strong>해석:</strong> 500·1,000 RPS 표본에는 Hikari pending 포화와 InnoDB 행 락 대기가 함께 관찰된다. 따라서 현재로서는 “풀만 작다”가 아니라, 풀 앞 대기와 동일 재고 행의 DB 경합이 혼재한 병목이다.</p><p class="fact warn"><strong>판단:</strong> MySQL 기준선의 100장/1,000장 정합성은 아래 유효 run에서 통과했지만, 10초 10,000명·p95 2초 목표는 아직 미충족/미검증이다. 풀 확대 반복 비교 전에는 MySQL 방식을 목표 충족으로 승인할 수 없다.</p></section>
<section><h2>2. 고객 기준과 검증 경계</h2><ul><li>동일 행사·회원 최대 1장: <code>coupon(user_id, stock_id)</code> 유니크 키 및 <code>coupon_issue_request</code> 유니크 키로 DB 검증.</li><li>초과 발급 금지: <code>UPDATE coupon_stock SET remain=remain-1 WHERE id=? AND remain&gt;0</code> affected row와 쿠폰 수/잔여 재고로 검증.</li><li>접수 p95 2초, 최종 결과 3분: 본 실험은 서버 HTTP 및 동기 발급만 측정한다. 브라우저 화면 시간은 미측정이다.</li><li>선착순: 원장 AUTO_INCREMENT ID는 서비스가 확정한 접수 순서의 기록 후보다. Redis 락 획득 전 외부 도착 순서를 저장하지 않으므로 “외부 요청 시작 순서” 준수는 검증 불가다.</li></ul></section>
<section><h2>3. 실제 구조·락·환경</h2><p>부하 전용 <code>mysql-loadtest</code> 프로필에서만 Redis 분산 락 AOP를 끈 뒤, 동일 Facade를 호출한다. 접수 원장 INSERT는 별도 트랜잭션이고, 재고 조건부 UPDATE와 <code>INSERT IGNORE coupon</code>은 <code>CouponService.issueCoupon()</code>의 하나의 트랜잭션이다. Named Lock은 사용하지 않았다. 운영 경로의 Redis 락 설정은 보존했다.</p><p>MySQL 8.0.46 · REPEATABLE-READ · max_connections 151 · 앱 2개(8082/8083) · Hikari max=10/앱, minIdle=10, connectionTimeout=30s · Tomcat max threads=200/앱. Redisson 클라이언트 연결은 앱 초기화에 존재하나, 이 프로필에서는 락 AOP가 호출되지 않는다.</p></section>
<section><h2>4. 결과 비교</h2><p>각 행의 원본은 <code>runs/&lt;run_id&gt;/</code>이다. <em>실제/누락</em>은 k6 iteration 수/dropped_iterations이며, 소진 409는 업무 응답이지 네트워크 실패가 아니다.</p><table><thead><tr><th>run_id</th><th>수량</th><th>목표</th><th>실제/누락</th><th>발급/소진</th><th>HTTP p50/p95/p99</th><th>2초 내 업무응답</th><th>Hikari active/max, pending 최대</th><th>락 대기 표본, 누적</th><th>정합성</th></tr></thead><tbody>${rows || '<tr><td colspan="10">유효 실행 없음</td></tr>'}</tbody></table><p>${invalid.length ? `무효 실행: ${invalid.map(r => `<code>${esc(r.name)}</code>`).join(', ')} — k6 발급 성공 계수와 DB 쿠폰 수가 달라 고유 회원 ID 생성 오류로 제외했다. 원본은 보존한다.` : '무효 실행 없음.'}</p></section>
<section><h2>5. 시각화와 병목 증거</h2><div class="grid"><div><h3>풀 10 고정: 목표 RPS별 HTTP p95</h3>${rateChart}<p>단위 ms. 표본 ${rateRuns.length}개. 서로 다른 조건의 점을 연속 성능 곡선으로 해석하지 않는다.</p></div><div><h3>${esc(evidenceRun?.name || '없음')}의 Hikari pending</h3>${hikariChart}<p>단위 대기 스레드, 실제 폴링 간격은 Docker MySQL 조회 오버헤드로 1초보다 길 수 있다.</p></div><div><h3>${esc(evidenceRun?.name || '없음')}의 data_lock_waits 행 수</h3>${lockChart}<p>단위 잠금 대기 관계 수. 순간 표본이므로 짧은 대기를 놓칠 수 있다.</p></div><div><h3>증거 해석</h3><p>Hikari active는 SQL 실행 중 수가 아니라 애플리케이션이 빌린 연결 수다. pending은 연결을 아직 얻지 못한 요청 스레드다. SQL 시간에는 DB 락 대기가 포함될 수 있으므로 이를 합산하지 않았다.</p></div></div></section>
<section><h2>6. 선택지</h2><table><tr><th>선택</th><th>현재 근거</th><th>판단/다음 검증</th></tr><tr><td>현 상태 유지</td><td>100 RPS·1,000장 정합성 통과</td><td>10,000명 목표와 2초 p95 근거가 없어 불가</td></tr><tr><td>풀 조정</td><td>풀 10에서 pending 관찰</td><td>5/10/20/40 후보 중 DB 연결 예산 내 후보를 같은 500 RPS로 3회 반복; p95·dropped·락·CPU/I/O·정합성을 같이 비교</td></tr><tr><td>SQL/트랜잭션 개선</td><td>동일 재고 조건부 UPDATE에서 InnoDB 대기 표본 증가</td><td>차단 SQL/트랜잭션 상세 샘플과 DB 자원 상태를 추가 확보한 후 별도 실험으로만 변경</td></tr><tr><td>접수/발급 분리</td><td>현재는 동기 구조라 풀·락 대기가 HTTP 응답에 직접 반영</td><td>2초 접수/3분 최종 결과를 분리할 필요가 확인되면 순서·복구 요구와 함께 설계. 이번 결과로 분산 락 성능은 판단하지 않음</td></tr></table></section>
<section><h2>7. 한계·재현</h2><p>미실행: 풀 크기별 3회 반복, 앱/DB/부하발생기 CPU·메모리·GC·디스크 I/O, 커넥션 acquire/use histogram, 차단 SQL 상세, 브라우저 표시 시간, 장애 복구, 오픈 순간 집중 패턴, 장시간 안정성. 1,000 RPS run은 dropped_iterations 때문에 목표 부하 통과가 아니다.</p>${outOfScope.length ? `<p class="warn fact">이번 비교 대상이 아닌 실행 ${outOfScope.length}건(${outOfScope.map(r=>esc(r.name)).join(', ')})은 Redis 분산 락 비교용으로 만들어졌으나 "Redis 분산 락은 이번 비교 대상에 추가하지 않는다"는 요구에 따라 이 보고서의 표·차트에서 제외했다. 원본은 <code>runs/</code>에 남아있다.</p>` : ''}<p>실행: <code>scripts/loadtest/setup-isolated-db.sh</code> → 두 앱을 <code>mysql-loadtest</code> 프로필/서로 다른 포트로 시작 → <code>RUN_ID=... STOCK_TOTAL=1000 ISSUE_RATE=500 DURATION=10s LOADTEST_HIKARI_MAX=10 scripts/loadtest/run-arrival-rate.sh</code> → <code>node scripts/loadtest/generate-report.js</code>.</p></section></main></body></html>`;
fs.writeFileSync(path.join(reportDir, 'report.html'), html);
console.log(path.join(reportDir, 'report.html'));
