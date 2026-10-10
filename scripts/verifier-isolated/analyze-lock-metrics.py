#!/usr/bin/env python3
"""stock 경로의 Redis 락 지표(coupon.redis_lock.acquire/hold)를 두 앱의 before/after .prom 원본에서 수집한다.
사용: analyze-lock-metrics.py <run_dir> <out_dir>   (읽는 파일: app1-before.prom app1-after.prom app2-before.prom app2-after.prom, 선택: app1.log app2.log)
원칙: 앱별 보고가 기본. 두 앱 합산은 (a) 두 앱 모두 reset 없음 (b) bucket le 집합이 동일할 때만 bucket 단위로 합산한다.
      p95 평균(앱별 분위수의 평균)은 만들지 않는다. 분위수는 누적 bucket 에서 '해당 분위가 속한 le 상한'으로만 보고한다(보수적 구간 추정).
      누락/reset(after<before)은 MISSING/RESET 으로 구분하고 0 으로 대체하지 않는다.
hold 는 DistributedLockAop 에서 REQUIRES_NEW 트랜잭션 경계 전체(DB connection 획득~commit 포함)를 감싼다 -> 순수 Redis 시간이 아니다.
tryLock 의 비-InterruptedException 예외(Redis 오류 등)는 acquire timer 에 기록되지 않는다 -> http_server_requests count 와 로그로 대조한다.
"""
import collections, json, pathlib, re, sys

run_dir = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+([0-9.eE+\-]+|NaN|\+Inf|-Inf)\s*$')
LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')

def load(path):
    if not path.exists(): return None
    rows = []
    for l in path.read_text(errors="replace").splitlines():
        m = LINE.match(l)
        if m: rows.append((m.group(1), dict(LABEL.findall(m.group(3) or "")), float(m.group(4)) if m.group(4) not in ("NaN",) else float("nan")))
    return rows

def fam(rows, name, pred=lambda lab: True):
    """name_count/sum/bucket 값을 {labels-key: ...} 로 모은다. bucket 은 {le: value}."""
    res = collections.defaultdict(lambda: {"count": None, "sum": None, "buckets": {}})
    for n, lab, v in rows:
        if not n.startswith(name + "_") or not pred(lab): continue
        suf = n[len(name) + 1:]
        key = tuple(sorted((k, x) for k, x in lab.items() if k not in ("le", "application")))
        if suf == "seconds_count": res[key]["count"] = v
        elif suf == "seconds_sum": res[key]["sum"] = v
        elif suf == "seconds_bucket": res[key]["buckets"][lab["le"]] = v
    return res

def delta(b, a):
    """b/a: fam 결과 항목. 반환: (status, count_delta, sum_delta, bucket_delta{le:v})"""
    if b is None or a is None or a["count"] is None or b["count"] is None: return "MISSING", None, None, {}
    if a["count"] < b["count"] or any(a["buckets"].get(le, 0) < v for le, v in b["buckets"].items()): return "RESET", None, None, {}
    bd = {le: a["buckets"].get(le, 0) - b["buckets"].get(le, 0) for le in a["buckets"]}
    return "OK", a["count"] - b["count"], (a["sum"] or 0) - (b["sum"] or 0), bd

def upper_le_for_quantile(bd, q):
    total = bd.get("+Inf")
    if not total: return None
    items = sorted(((float("inf") if le == "+Inf" else float(le)), le, v) for le, v in bd.items())
    for _, le_str, v in items:
        if v >= q * total: return le_str
    return None

def summarize(metric, outcome_label=True):
    result = {}
    per_app = {}
    for app in ("app1", "app2"):
        b, a = load(run_dir / f"{app}-before.prom"), load(run_dir / f"{app}-after.prom")
        if b is None or a is None:
            per_app[app] = {"status": "MISSING (before/after prom file absent)"}; continue
        fb, fa = fam(b, metric), fam(a, metric)
        keys = set(fb) | set(fa)
        per_app[app] = {}
        if not keys: per_app[app] = {"status": f"MISSING (metric {metric} not exported)"}; continue
        for k in sorted(keys):
            st, c, s, bd = delta(fb.get(k), fa.get(k))
            tag = ",".join(f"{x}={y}" for x, y in k) or "(no-tags)"
            ent = {"status": st}
            if st == "OK":
                ent.update({"count_delta": c, "sum_s_delta": round(s, 6), "mean_ms": round(1000 * s / c, 3) if c else None,
                            "quantile_upper_le_s": {"p50": upper_le_for_quantile(bd, .5), "p95": upper_le_for_quantile(bd, .95), "p99": upper_le_for_quantile(bd, .99)},
                            "_bucket_delta": bd})
            per_app[app][tag] = ent
    # 두 앱 합산(조건 충족 시에만)
    combined = {}
    tags = set(t for app in per_app.values() if isinstance(app, dict) for t in app if t != "status")
    for tag in sorted(tags):
        e1, e2 = per_app.get("app1", {}).get(tag), per_app.get("app2", {}).get(tag)
        if not (e1 and e2 and e1.get("status") == "OK" and e2.get("status") == "OK"):
            combined[tag] = {"status": "NOT_COMBINED (an app is missing/reset for this series; report per-app)"}; continue
        if set(e1["_bucket_delta"]) != set(e2["_bucket_delta"]):
            combined[tag] = {"status": "NOT_COMBINED (bucket sets differ)"}; continue
        bd = {le: e1["_bucket_delta"][le] + e2["_bucket_delta"][le] for le in e1["_bucket_delta"]}
        c, s = e1["count_delta"] + e2["count_delta"], e1["sum_s_delta"] + e2["sum_s_delta"]
        combined[tag] = {"status": "OK(bucket-summed)", "count_delta": c, "mean_ms": round(1000 * s / c, 3) if c else None,
                         "quantile_upper_le_s": {"p50": upper_le_for_quantile(bd, .5), "p95": upper_le_for_quantile(bd, .95), "p99": upper_le_for_quantile(bd, .99)}}
    for app in per_app.values():
        for ent in app.values():
            if isinstance(ent, dict): ent.pop("_bucket_delta", None)
    return {"per_app": per_app, "combined": combined}

acq = summarize("coupon_redis_lock_acquire"); hold = summarize("coupon_redis_lock_hold")

# 미계측 구간 대조: 락 타이머 acquire count(모든 outcome) vs 서버가 처리한 loadtest 요청 수
def http_count(app):
    b, a = load(run_dir / f"{app}-before.prom"), load(run_dir / f"{app}-after.prom")
    if b is None or a is None: return None
    def tot(rows): return sum(v for n, lab, v in rows if n == "http_server_requests_seconds_count" and lab.get("uri", "").startswith("/internal/loadtest/coupons"))
    return tot(a) - tot(b)
recon = {}
for app in ("app1", "app2"):
    h = http_count(app)
    acquired = acq["per_app"].get(app, {})
    c = sum(e.get("count_delta", 0) for t, e in acquired.items() if isinstance(e, dict) and e.get("status") == "OK") if acquired else None
    log = run_dir / f"{app}.log"
    log_counts = None
    if log.exists():
        txt = log.read_text(errors="replace")
        log_counts = {"LockAcquisitionFailedException": txt.count("LockAcquisitionFailedException"), "Lock acquisition interrupted": txt.count("Lock acquisition interrupted"),
                      "ERROR_lines": len(re.findall(r"\bERROR\b", txt)), "RedisException/timeout lines": len(re.findall(r"Redis[A-Za-z]*Exception|RedisTimeout", txt))}
    recon[app] = {"http_loadtest_requests_delta": h, "lock_acquire_timer_count_all_outcomes": c,
                  "untimed_estimate(http - timer, NOT assumed zero)": (h - c) if (h is not None and c is not None) else "UNKNOWN",
                  "app_log_counts": log_counts if log_counts is not None else "MISSING (app log not captured)"}
summary = {"acquire": acq, "hold": hold, "untimed_reconciliation": recon,
           "notes": ["hold wraps the whole REQUIRES_NEW transaction (DB connection acquire..commit) - not pure Redis time",
                     "default wait 5s / lease 3s (DistributedLock annotation defaults); see lock-annotation-sites.txt for per-site config",
                     "same metric names are used by the async worker and recovery worker sites; metrics carry no method tag -> separate by running with COUPON_ISSUE_WORKER_MODE=off and checking worker logs",
                     "non-interrupt tryLock exceptions are NOT timed; compare with http 5xx/log counts, do not treat the gap as zero"]}
(out / "lock-metrics.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
print(json.dumps(summary, indent=2, ensure_ascii=False))
