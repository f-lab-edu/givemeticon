#!/usr/bin/env python3
"""접수 생성(POST만)과 관찰을 분리한 실행의 사후 분석. 조회(폴링) 부하 없이 POLL_BUDGET_MS=0 으로 실행한 k6 원본
(k6-failures.log)의 회원별 최초 POST 와 DB 서버 시각(accepted_at/finalized_at)을 회원 단위로 대조한다.
사용: analyze-separated.py <run_dir> <mysql_container> <database> <event_id> [budget_ms=180000]
산출: separated-summary.json, separated-members.tsv (분모 = k6가 보낸 모든 최초 POST. CHECKING/5xx/timeout 포함)
전제: k6 호스트와 MySQL 컨테이너 시계가 같은 UTC 기준(같은 호스트). 다르면 completion 값을 신뢰하지 않는다.
"""
import collections, json, pathlib, re, subprocess, sys

run_dir = pathlib.Path(sys.argv[1]); container, database, event_id = sys.argv[2], sys.argv[3], sys.argv[4]
budget_ms = int(sys.argv[5]) if len(sys.argv) > 5 else 180000
MSG = re.compile(r'msg="(.*)"\s*$')

def parse(path):
    out = []
    if not path.exists(): return out
    for raw in path.read_text(errors="replace").splitlines():
        line = raw.strip(); m = MSG.search(line)
        payload = m.group(1) if m else (line if line.startswith("{") else None)
        if payload is None: continue
        try: out.append(json.loads(payload.replace('\\"', '"').replace("\\\\", "\\")))
        except ValueError: pass
    return out

def pct(vals, p):
    if not vals: return None
    s = sorted(vals); return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]

log = parse(run_dir / "k6-failures.log")
adm = {}
for r in log:
    if r.get("kind") == "admission": adm[r["memberId"]] = r   # 회원당 최초 POST 1건
dups = [r for r in log if r.get("kind") == "duplicate"]

# 비밀번호는 컨테이너 env에서 읽어 로그/인자에 노출하지 않는다.
sql = (f"SELECT member_id, acceptance_sequence, status, CAST(UNIX_TIMESTAMP(accepted_at)*1000 AS UNSIGNED), "
       f"IFNULL(CAST(UNIX_TIMESTAMP(finalized_at)*1000 AS UNSIGNED),'NULL') FROM coupon_application WHERE event_id={int(event_id)}")
res = subprocess.run(["docker", "exec", container, "sh", "-c",
    f'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" {database} --batch --skip-column-names -e "{sql}" 2>/dev/null'],
    capture_output=True, text=True, check=True)
db = {}
for line in res.stdout.splitlines():
    p = line.split("\t")
    if len(p) == 5: db[int(p[0])] = dict(seq=int(p[1]), status=p[2], accepted=int(p[3]), finalized=None if p[4] == "NULL" else int(p[4]))

cats = collections.Counter(r.get("category") for r in adm.values())
lat = collections.defaultdict(list)
for r in adm.values(): lat[r.get("category")].append(r.get("durationMs", 0))
all_lat = [x for v in lat.values() for x in v]

rows, comp, unresolved, within = [], [], 0, 0
for m, r in adm.items():
    d = db.get(m); sent = r.get("sentAtMs")
    if d is None:
        rows.append((m, r.get("category"), "NOT_IN_DB", "", "")); continue
    c = (d["finalized"] - sent) if d["finalized"] and sent else None
    if d["status"] in ("ISSUED", "SOLD_OUT") and c is not None:
        comp.append(c); within += c <= budget_ms
    else: unresolved += 1
    rows.append((m, r.get("category"), d["status"], c if c is not None else "", d["seq"]))
(run_dir / "separated-members.tsv").write_text("memberId\tadmissionCategory\tdbStatus\tfirstPostToFinalMs\tseq\n" + "\n".join("\t".join(map(str, x)) for x in rows))

sent_ts = sorted(r["sentAtMs"] for r in adm.values() if r.get("sentAtMs"))
span_ms = (sent_ts[-1] - sent_ts[0]) if len(sent_ts) > 1 else None
summary = {
    "first_post_total_denominator": len(adm),
    "admission_category_counts": dict(cats),
    "send_span_ms": span_ms,
    "within_first_10s_sent": sum(1 for t in sent_ts if t - sent_ts[0] < 10000) if sent_ts else 0,
    "admission_latency_ms_all": {"p50": pct(all_lat, 50), "p95": pct(all_lat, 95), "p99": pct(all_lat, 99)},
    "admission_latency_ms_by_category": {c: {"n": len(v), "p50": pct(v, 50), "p95": pct(v, 95), "p99": pct(v, 99)} for c, v in lat.items()},
    "db_rows": len(db),
    "db_status_counts": dict(collections.Counter(d["status"] for d in db.values())),
    "members_with_post_not_in_db_by_category": dict(collections.Counter(r[1] for r in rows if r[2] == "NOT_IN_DB")),
    "db_members_without_post_record": sorted(set(db) - set(adm))[:20],
    "db_members_without_post_record_count": len(set(db) - set(adm)),
    "final_within_budget_count": within, "budget_ms": budget_ms,
    "final_within_budget_rate_of_denominator_pct": round(within / len(adm) * 100, 2) if adm else None,
    "final_within_budget_rate_of_non_closed_pct": round(within / max(1, len(adm) - cats.get("event_closed", 0)) * 100, 2),
    "unresolved_db_rows": unresolved,
    "first_post_to_final_ms": {"p50": pct(comp, 50), "p95": pct(comp, 95), "p99": pct(comp, 99), "max": max(comp) if comp else None},
    "duplicate_post_records": len(dups),
}
(run_dir / "separated-summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
print(json.dumps(summary, indent=2, ensure_ascii=False))
