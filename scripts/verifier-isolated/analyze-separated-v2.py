#!/usr/bin/env python3
"""analyze-separated.py 보완판(v1은 보존). 원본 run_dir 은 읽기만 하고 산출물은 <out_dir> 에 쓴다.
사용: analyze-separated-v2.py <run_dir> <out_dir> <mysql_container> <database> <event_id> <member_id_start> [budget_ms=180000]
v1 대비 변경:
 - 로그 parse 오류/중복 최초 POST/누락 member 를 탐지하고 k6 summary 의 actual 요청 수(admission_attempts, iterations)와 대조
 - finalized_at 은 트랜잭션 내부 상태 UPDATE 시각이다(commit/클라이언트 확인 latency 아님) -> status_update_ts 지표로 명명
 - fast-sampler 의 상주 세션 관찰로 commit 후 가시 시각, 관측 간격, 시계 offset 오차를 별도 계산
 - 분모 분리: 전체(최초 POST) / 확정 / 업무거절(ENDED) / 미확정(CHECKING,5xx,timeout,오류,DB 미존재·비종결)
 - Hikari: fast-prom.csv 로 최대 active/pending, acquire count/sum delta, 표본 간격. 없으면 MISSING 으로 기록
"""
import collections, csv, json, pathlib, re, statistics, subprocess, sys

run_dir = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
container, database, event_id, member_start = sys.argv[3], sys.argv[4], int(sys.argv[5]), int(sys.argv[6])
budget_ms = int(sys.argv[7]) if len(sys.argv) > 7 else 180000
MSG = re.compile(r'msg="(.*)"\s*$')

def pct(v, p):
    if not v: return None
    s = sorted(v); return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]
def dist(v): return {"n": len(v), "p50": pct(v, 50), "p95": pct(v, 95), "p99": pct(v, 99), "max": max(v) if v else None}

# ---- 1) k6 원본 로그 파싱 + 무결성
lines = (run_dir / "k6-failures.log").read_text(errors="replace").splitlines() if (run_dir / "k6-failures.log").exists() else []
records, parse_errors, candidate_lines = [], 0, 0
for raw in lines:
    line = raw.strip(); m = MSG.search(line)
    payload = m.group(1) if m else (line if line.startswith("{") else None)
    if payload is None: continue
    candidate_lines += 1
    try: records.append(json.loads(payload.replace('\\"', '"').replace("\\\\", "\\")))
    except ValueError: parse_errors += 1
adm_all = [r for r in records if r.get("kind") == "admission"]
per_member = collections.defaultdict(list)
for r in adm_all: per_member[r["memberId"]].append(r)
dup_first_post = {m: len(v) for m, v in per_member.items() if len(v) > 1}
adm = {m: v[0] for m, v in per_member.items()}
ksum = json.loads((run_dir / "k6-summary.json").read_text())["metrics"]
actual_iterations = ksum.get("iterations", {}).get("count")
actual_attempts = ksum.get("admission_attempts", {}).get("count")
expected_ids = set(range(member_start, member_start + (actual_iterations or 0)))
missing_post_records = sorted(expected_ids - set(adm))
unexpected_ids = sorted(set(adm) - expected_ids)

# ---- 2) DB (읽기만)
sql = (f"SELECT member_id, acceptance_sequence, status, CAST(UNIX_TIMESTAMP(accepted_at)*1000 AS UNSIGNED), "
       f"IFNULL(CAST(UNIX_TIMESTAMP(finalized_at)*1000 AS UNSIGNED),'NULL') FROM coupon_application WHERE event_id={event_id}")
res = subprocess.run(["docker", "exec", container, "sh", "-c",
    f'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" {database} --batch --skip-column-names -e "{sql}" 2>/dev/null'], capture_output=True, text=True, check=True)
db = {}
for line in res.stdout.splitlines():
    p = line.split("\t")
    if len(p) == 5: db[int(p[0])] = dict(seq=int(p[1]), status=p[2], accepted=int(p[3]), finalized=None if p[4] == "NULL" else int(p[4]))

# ---- 3) 분류(분모 = 모든 최초 POST)
FINAL = ("ISSUED", "SOLD_OUT")
buckets = collections.Counter(); rows = []; upd = []; within = 0
for m, r in adm.items():
    d = db.get(m); cat = r.get("category"); sent = r.get("sentAtMs")
    if d and d["status"] in FINAL:
        b = "confirmed_final"; c = (d["finalized"] - sent) if d["finalized"] and sent else None
        if c is not None: upd.append(c); within += c <= budget_ms
    elif cat == "event_closed" and not d: b = "business_rejected_ENDED"; c = None
    else: b = "unconfirmed"; c = None   # CHECKING/5xx/timeout/오류/DB미존재/DB비종결
    buckets[b] += 1
    rows.append((m, cat, d["status"] if d else "NOT_IN_DB", b, c if c is not None else ""))
buckets["missing_post_record(no log line)"] = len(missing_post_records)
total_denominator = len(adm) + len(missing_post_records)
(out / "members-v2.tsv").write_text("memberId\tadmissionCategory\tdbStatus\tbucket\tfirstPostToStatusUpdateTsMs\n" + "\n".join("\t".join(map(str, x)) for x in rows))

# ---- 4) 관찰자(commit 후 가시)
obs = {}
dbcsv = run_dir / "fast-db.csv"
if dbcsv.exists():
    S = list(csv.DictReader(open(dbcsv)))
    for s in S: s["b"], s["a"], s["srv"], s["fin"], s["unres"], s["tot"] = int(s["host_before_ms"]), int(s["host_after_ms"]), int(s["server_ms"]), int(s["final_visible"]), int(s["unresolved"]), int(s["total"])
    gaps = [S[i + 1]["b"] - S[i]["b"] for i in range(len(S) - 1)]
    rtts = [s["a"] - s["b"] for s in S]
    offs = [(s["srv"] - (s["b"] + s["a"]) / 2, (s["a"] - s["b"]) / 2) for s in S]   # (offset, 오차한계)
    best = min(offs, key=lambda x: x[1]) if offs else None
    # 가시 지연 추정: 관찰된 최종 행 수 N 에 대해, N번째로 이른 finalized_at 과 관찰 server 시각의 차
    fin_sorted = sorted(d["finalized"] for d in db.values() if d["finalized"])
    lags, prev = [], 0
    for s in S:   # 가시 행 수가 증가한 표본만 사용(정지 구간 표본은 지연을 부풀린다)
        n = s["fin"]
        if n > prev and n <= len(fin_sorted): lags.append(s["srv"] - fin_sorted[n - 1])
        prev = max(prev, n)
    conv = next((s for s in S if s["unres"] == 0 and s["tot"] > 0 and s["fin"] == len(fin_sorted)), None)
    first_send = min((r["sentAtMs"] for r in adm.values() if r.get("sentAtMs")), default=None)
    obs = {"samples": len(S), "interval_ms": dist(gaps), "query_rtt_ms": dist(rtts),
           "clock_offset_ms_best_sample": {"offset_server_minus_host": best[0], "error_bound": best[1]} if best else None,
           "clock_offset_ms_spread": {"min": min(o[0] for o in offs), "max": max(o[0] for o in offs)} if offs else None,
           "visibility_lag_estimate_ms(newly-visible samples only: srv_obs_time - newest visible row status_update_ts; lower-bound-ish, <= interval resolution)": dist(lags),
           "run_level_convergence_observed_ms_after_first_send(host clock, upper bound within interval)":
               (conv["a"] - first_send) if conv and first_send else None,
           "per_member_strict_180s_observation": "NOT_VERIFIED (observer samples aggregate counts only)"}
else: obs = {"status": "MISSING fast-db.csv (observer not run)"}

# ---- 5) Hikari
hk = {}
pc = run_dir / "fast-prom.csv"
if pc.exists():
    P = [r for r in csv.DictReader(open(pc))]
    ok = [r for r in P if r["active"] not in ("ERR", "NA")]
    def num(r, k): return float(r[k])
    per_app = {}
    for app in ("app1", "app2"):
        a = [r for r in ok if r["app"] == app]
        if not a: per_app[app] = "MISSING"; continue
        ts = [int(r["host_ms"]) for r in a]
        has_acq = a[0]["acq_count"] not in ("NA",)
        per_app[app] = {"samples": len(a), "scrape_errors": len([r for r in P if r["app"] == app and r["active"] == "ERR"]),
            "interval_ms": dist([ts[i + 1] - ts[i] for i in range(len(ts) - 1)]),
            "WARNING": ("scrape failures during run: max_active/max_pending may MISS the saturated peak (app did not answer /actuator within 1s)"
                        if any(r["app"] == app and r["active"] == "ERR" for r in P) else None),
            "max_active": max(num(r, "active") for r in a), "max_pending": max(num(r, "pending") for r in a), "pool_max": num(a[0], "max"),
            "timeout_total_delta": (num(a[-1], "timeout_total") - num(a[0], "timeout_total")) if a[0]["timeout_total"] != "NA" else "MISSING",
            "acquire": ({"count_delta": num(a[-1], "acq_count") - num(a[0], "acq_count"),
                         "sum_s_delta": num(a[-1], "acq_sum_s") - num(a[0], "acq_sum_s"),
                         "mean_ms": (1000 * (num(a[-1], "acq_sum_s") - num(a[0], "acq_sum_s")) / max(1, num(a[-1], "acq_count") - num(a[0], "acq_count"))),
                         "max_gauge_s_peak": max(num(r, "acq_max_s") for r in a), "histogram_buckets": "NOT_EXPORTED (summary count/sum/max only)"}
                        if has_acq else "MISSING")}
    hk = per_app
else: hk = {"status": "MISSING fast-prom.csv"}

def prom_val(path, name):
    if not path.exists(): return None
    t = 0.0; f = False
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith(name + "{") or line.startswith(name + " "):
            try: t += float(line.rsplit(" ", 1)[1]); f = True
            except ValueError: pass
    return t if f else None
srv = {}
for app, tag in (("app1", "app1"), ("app2", "app2")):
    b, a = run_dir / f"{tag}-before.prom", run_dir / f"{tag}-after.prom"
    row = {}
    for key, cnt, sm in (("batch_queue_wait", "coupon_admission_batch_queue_wait_seconds_count", "coupon_admission_batch_queue_wait_seconds_sum"),
                         ("admission_tx", "coupon_admission_transaction_seconds_count", "coupon_admission_transaction_seconds_sum")):
        c0, c1, s0, s1 = prom_val(b, cnt), prom_val(a, cnt), prom_val(b, sm), prom_val(a, sm)
        row[key] = {"count_delta": c1 - c0, "mean_ms": round(1000 * (s1 - s0) / (c1 - c0), 2)} if None not in (c0, c1, s0, s1) and c1 > c0 else "MISSING"
    row["batch_queue_wait_max_s_after(cumulative gauge)"] = prom_val(a, "coupon_admission_batch_queue_wait_seconds_max")
    srv[app] = row

sent_ts = sorted(r["sentAtMs"] for r in adm.values() if r.get("sentAtMs"))
summary = {
    "integrity": {"log_lines_total": len(lines), "candidate_json_lines": candidate_lines, "parse_errors": parse_errors,
        "admission_records": len(adm_all), "unique_first_post_members": len(adm), "members_with_multiple_admission_records": len(dup_first_post),
        "k6_iterations": actual_iterations, "k6_admission_attempts_counter": actual_attempts,
        "missing_post_records": len(missing_post_records), "missing_post_record_examples": missing_post_records[:10],
        "unexpected_member_ids": len(unexpected_ids),
        "verdict": "OK" if (not parse_errors and not dup_first_post and not missing_post_records and not unexpected_ids and actual_iterations == len(adm) == actual_attempts) else "MISMATCH - do not trust denominators"},
    "denominator_total_first_posts": total_denominator,
    "send_span_ms": (sent_ts[-1] - sent_ts[0]) if len(sent_ts) > 1 else None,
    "buckets": dict(buckets),
    "headline_final_within_budget": {"numerator": within, "denominator": total_denominator, "rate_pct": round(100 * within / total_denominator, 2) if total_denominator else None, "budget_ms": budget_ms,
        "note": "status_update_ts(DB tx UPDATE time) based; strict client/commit observation NOT verified per member"},
    "conditional_rate_excluding_ENDED_pct": round(100 * within / max(1, total_denominator - buckets["business_rejected_ENDED"]), 2),
    "db": {"rows": len(db), "status_counts": dict(collections.Counter(d["status"] for d in db.values())),
           "db_unresolved(PENDING/CHECKING)": sum(1 for d in db.values() if d["status"] not in FINAL),
           "db_reconciliation_unconfirmed_members(DB-reconciliation basis ONLY; client-side confirmation not assessed)": buckets["unconfirmed"] + len(missing_post_records),
           "db_final_but_client_unconfirmed(DB result known, client saw timeout/CHECKING/error)": sum(1 for m, r in adm.items() if db.get(m) and db[m]["status"] in FINAL and r.get("category") != "success")},
    "admission_latency_ms_by_category": {c: dist([r.get("durationMs", 0) for r in adm.values() if r.get("category") == c]) for c in set(r.get("category") for r in adm.values())},
    "first_post_to_status_update_ts_ms": dist(upd),
    "observer": obs, "hikari": hk, "server_side_admission_metrics_run_delta": srv,
    "caveat": "status_update_ts is the UPDATE timestamp inside the issuance transaction, NOT commit-visible/client-confirmed latency.",
}
(out / "summary-v2.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
print(json.dumps(summary, indent=2, ensure_ascii=False))
