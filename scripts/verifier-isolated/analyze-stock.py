#!/usr/bin/env python3
"""stock(Redisson+MySQL) 실행 사후 분석. k6 원본(stock-arrival-record.js 의 console 기록)과 DB 를 회원 단위로 대조한다.
사용: analyze-stock.py <run_dir> <out_dir> <mysql_container> <database> <stock_id> <user_id_start> [stock_total] [--dup-rate=0.10] [--db-fixture=<tsv>]
DB 조회는 컨테이너 내부 MYSQL_PWD 로 처리(호스트 argv 에 비밀번호 없음). --db-fixture <file> 은 대체 실행(테스트)용 TSV: user_id\\tcreated(1)
분모 = 모든 최초 요청. 200 응답 ≠ DB 확정(accept 모드는 이후 워커 처리), 클라이언트 timeout 이지만 DB 발급된 회원은 별도 집계.
"""
import collections, json, pathlib, re, subprocess, sys

args = [a for a in sys.argv[1:] if not a.startswith('--')]
fixture = next((a.split('=', 1)[1] for a in sys.argv[1:] if a.startswith('--db-fixture=')), None)
dup_rate = float(next((a.split('=', 1)[1] for a in sys.argv[1:] if a.startswith('--dup-rate=')), 0.10))   # 0 이면 고유 요청만(보조 실험)
run_dir = pathlib.Path(args[0]); out = pathlib.Path(args[1]); out.mkdir(parents=True, exist_ok=True)
container, database, stock_id, user_start = args[2], args[3], int(args[4]), int(args[5])
stock_total = int(args[6]) if len(args) > 6 else None
MSG = re.compile(r'msg="(.*)"\s*$')

def pct(v, p):
    if not v: return None
    s = sorted(v); return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]
def dist(v): return {"n": len(v), "p50": pct(v, 50), "p95": pct(v, 95), "p99": pct(v, 99), "max": max(v) if v else None}

lines = (run_dir / "k6-failures.log").read_text(errors="replace").splitlines() if (run_dir / "k6-failures.log").exists() else []
recs, parse_errors, cand = [], 0, 0
for raw in lines:
    line = raw.strip(); m = MSG.search(line)
    payload = m.group(1) if m else (line if line.startswith("{") else None)
    if payload is None: continue
    cand += 1
    try: recs.append(json.loads(payload.replace('\\"', '"').replace("\\\\", "\\")))
    except ValueError: parse_errors += 1
recs = [r for r in recs if r.get("kind") == "stock_request"]
dups = [r for r in recs if r.get("attempt", 1) == 2]          # 결정적 중복 재요청(같은 회원, 다른 앱)
recs = [r for r in recs if r.get("attempt", 1) == 1]          # 최초 요청만 분모
per = collections.defaultdict(list)
for r in recs: per[r["userId"]].append(r)
multi = {u: len(v) for u, v in per.items() if len(v) > 1}
first = {u: v[0] for u, v in per.items()}
ksum = json.loads((run_dir / "k6-summary.json").read_text())["metrics"] if (run_dir / "k6-summary.json").exists() else {}
iters = ksum.get("iterations", {}).get("count")
missing = sorted(set(range(user_start, user_start + (iters or 0))) - set(first))

if fixture:
    db_users = {int(l.split("\t")[0]) for l in pathlib.Path(fixture).read_text().splitlines() if l.strip()}
    dup_users = 0; coupon_rows = len(db_users)
else:
    def q(sql):
        r = subprocess.run(["docker", "exec", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names "$1" -e "$2"', "sh", database, sql],
                           capture_output=True, text=True, check=True)
        return [l.split("\t") for l in r.stdout.splitlines()]
    db_users = {int(x[0]) for x in q(f"SELECT user_id FROM coupon WHERE stock_id={stock_id}")}
    dup_users = int(q(f"SELECT COUNT(*) FROM (SELECT user_id FROM coupon WHERE stock_id={stock_id} GROUP BY user_id HAVING COUNT(*)>1) d")[0][0])
    coupon_rows = int(q(f"SELECT COUNT(*) FROM coupon WHERE stock_id={stock_id}")[0][0])

cats = collections.Counter(r["category"] for r in first.values())
success_users = {u for u, r in first.items() if r["category"] == "success"}
missing_success_in_db = sorted(success_users - db_users)           # 200 응답인데 DB 쿠폰 없음(accept 모드면 워커 대기 중일 수 있음)
db_without_success = sorted(db_users - success_users)               # DB 에는 발급됐는데 클라이언트는 200 을 못 봄
db_client_unconfirmed = collections.Counter(first[u]["category"] if u in first else "no_post_record" for u in db_without_success)
over_issued = (coupon_rows - stock_total) if stock_total is not None and coupon_rows > stock_total else 0
lat = collections.defaultdict(list)
for r in first.values(): lat[r["category"]].append(r.get("durationMs", 0))
sent = sorted(r["sentAtMs"] for r in first.values() if r.get("sentAtMs"))
every = max(1, round(1 / dup_rate)) if dup_rate > 0 else None
expected_dup_users = {u for u in first if every and (u - user_start) % every == 0}
actual_dup_users = {r["userId"] for r in dups}
dup_cat = collections.Counter(r["category"] for r in dups)
in_window = [r for r in first.values() if r.get("inWindow", True)]
outside = [r for r in first.values() if not r.get("inWindow", True)]
summary = {
    "integrity": {"candidate_lines": cand, "parse_errors": parse_errors, "records": len(recs), "unique_users": len(first),
                  "users_with_multiple_records": len(multi), "k6_iterations": iters, "missing_post_records": len(missing),
                  "verdict": "OK" if (not parse_errors and not multi and not missing and iters == len(first) and not (expected_dup_users ^ actual_dup_users)) else "MISMATCH - do not trust denominators"},
    "denominator_first_requests": len(first) + len(missing),
    "send_span_ms": (sent[-1] - sent[0]) if len(sent) > 1 else None,
    "send_window": {"first_requests_in_window": len(in_window), "first_requests_outside_window": len(outside),
                    "outside_window_note": "boundary/late iterations (e.g. the +1 iteration at t=duration) are counted in the denominator but flagged; field inWindow absent => treated as in-window",
                    "outside_window_categories": dict(collections.Counter(r["category"] for r in outside))},
    "duplicates": {"configured_rate": dup_rate, "attempts_recorded": len(dups), "expected_users(deterministic seq%round(1/rate)==0)": len(expected_dup_users),
                   "missing_expected": len(expected_dup_users - actual_dup_users), "unexpected": len(actual_dup_users - expected_dup_users),
                   "category_counts": dict(dup_cat), "db_duplicate_users": dup_users, "observed_rate_of_first_requests": round(len(dups) / max(1, len(first)), 4)},
    "client_category_counts": dict(cats),
    "latency_ms_by_category": {c: dist(v) for c, v in lat.items()},
    "latency_ms_all": dist([x for v in lat.values() for x in v]),
    "db": {"coupon_rows": coupon_rows, "duplicate_users": dup_users, "over_issued": over_issued},
    "success_response_but_not_in_db": len(missing_success_in_db),
    "db_issued_but_client_did_not_see_200": dict(db_client_unconfirmed),
    "note": "category success = HTTP 200 only. Requests the client could not confirm (timeout/error) may still be DB-issued; counted separately.",
}
(out / "stock-summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
(out / "stock-members.tsv").write_text("userId\tcategory\tstatus\tdurationMs\tinDb\n" + "\n".join(
    f"{u}\t{r['category']}\t{r['status']}\t{r.get('durationMs','')}\t{int(u in db_users)}" for u, r in sorted(first.items())))
print(json.dumps(summary, indent=2, ensure_ascii=False))
