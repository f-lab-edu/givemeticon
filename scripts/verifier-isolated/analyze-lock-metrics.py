#!/usr/bin/env python3
"""stock 경로의 Redis 락 지표(coupon.redis_lock.acquire/hold)를 두 앱의 before/after .prom 원본에서 수집한다.
사용: analyze-lock-metrics.py <run_dir> <out_dir>   (읽는 파일: app1-before.prom app1-after.prom app2-before.prom app2-after.prom, 선택: app1.log app2.log)
원칙: 앱별 보고가 기본. 두 앱 합산은 (a) 두 앱 모두 reset 없음 (b) bucket le 집합이 동일할 때만 bucket 단위로 합산한다.
      p95 평균(앱별 분위수의 평균)은 만들지 않는다. 분위수는 누적 bucket 에서 '해당 분위가 속한 le 상한'으로만 보고한다(보수적 구간 추정).
      누락/reset(after<before)은 MISSING/RESET 으로 구분하고 0 으로 대체하지 않는다.
      예외: before 에 시리즈가 없을 때 prom_baseline 의 본문 검사 + 수집 시점 provenance(<app>-scrape-provenance.json) + 등록 경로가 확인된 family 이면
      status=OK_INFERRED_ZERO_BASELINE 로 baseline 0 을 '추론'한다(직접 관측 OK 와 구분). provenance 가 없으면 MISSING.
      --allow-manual-inference 를 주면 본문 검사만 통과해도 CONDITIONAL_MANUAL_INFERENCE 로 표기한다(OK 가 아님, 과거 수집 사실로 귀속하지 않음).
hold 는 DistributedLockAop 에서 REQUIRES_NEW 트랜잭션 경계 전체(DB connection 획득~commit 포함)를 감싼다 -> 순수 Redis 시간이 아니다.
tryLock 의 비-InterruptedException 예외(Redis 오류 등)는 acquire timer 에 기록되지 않는다 -> http_server_requests count 와 로그로 대조한다.
"""
import collections, json, pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import prom_baseline as PB

ALLOW_MANUAL = "--allow-manual-inference" in sys.argv
argv = [x for x in sys.argv if x != "--allow-manual-inference"]
run_dir = pathlib.Path(argv[1]); out = pathlib.Path(argv[2]); out.mkdir(parents=True, exist_ok=True)
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+([0-9.eE+\-]+|NaN|\+Inf|-Inf)\s*$')
LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')

def load(path):
    r = PB.read_prom(path)
    return r["rows"] if r else None

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

def delta(b, a, lazy_zero=False):
    """b/a: fam 결과 항목. 반환: (status, count_delta, sum_delta, bucket_delta{le:v}). lazy_zero: before 부재를 baseline 0 으로 추론해도 되는지(증거 통과)"""
    if b is None and a is not None and a["count"] is not None and lazy_zero:
        return lazy_zero, a["count"], a["sum"] or 0, dict(a["buckets"])   # lazy_zero: 추론 status 문자열
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

def usable(st): return st.startswith("OK") or st == "CONDITIONAL_MANUAL_INFERENCE"


def evidence_for(app):
    return PB.scrape_evidence(PB.read_prom(run_dir / f"{app}-before.prom"), PB.read_prom(run_dir / f"{app}-after.prom"),
                              PB.read_provenance(run_dir / f"{app}-scrape-provenance.json"),
                              (run_dir / f"{app}-before.prom", run_dir / f"{app}-after.prom"))


def summarize(metric, outcome_label=True):
    result = {}
    per_app = {}
    for app in ("app1", "app2"):
        b, a = load(run_dir / f"{app}-before.prom"), load(run_dir / f"{app}-after.prom")
        if b is None or a is None:
            per_app[app] = {"status": "MISSING (before/after prom file absent)"}; continue
        fb, fa = fam(b, metric), fam(a, metric)
        ev = evidence_for(app)
        allowed, why = PB.lazy_zero_allowed(ev, metric, ALLOW_MANUAL)
        keys = set(fb) | set(fa)
        per_app[app] = {}
        if not keys: per_app[app] = {"status": f"MISSING (metric {metric} not exported)"}; continue
        for k in sorted(keys):
            st, c, s, bd = delta(fb.get(k), fa.get(k), lazy_zero=allowed)
            tag = ",".join(f"{x}={y}" for x, y in k) or "(no-tags)"
            ent = {"status": st}
            if k not in fb and k in fa:   # before 부재: 추론이면 근거를, 아니면 MISSING 사유를 남긴다
                ent["absent_before"] = {"inferred_zero_baseline": st == "OK_INFERRED_ZERO_BASELINE", "conditional_manual": st == "CONDITIONAL_MANUAL_INFERENCE", "reason": why,
                                        "structural_checks": ev["checks"], "provenance_checks": ev["provenance_checks"], "diagnostics": ev["diagnostics"]}
            if usable(st):
                ent.update({"count_delta": c, "sum_s_delta": round(s, 6), "mean_ms": round(1000 * s / c, 3) if c else None,
                            "quantile_upper_le_s": {"p50": upper_le_for_quantile(bd, .5), "p95": upper_le_for_quantile(bd, .95), "p99": upper_le_for_quantile(bd, .99)},
                            "_bucket_delta": bd})
            per_app[app][tag] = ent
    # 두 앱 합산(조건 충족 시에만)
    combined = {}
    tags = set(t for app in per_app.values() if isinstance(app, dict) for t in app if t != "status")
    for tag in sorted(tags):
        e1, e2 = per_app.get("app1", {}).get(tag), per_app.get("app2", {}).get(tag)
        if not (e1 and e2 and usable(e1.get("status", "")) and usable(e2.get("status", ""))):
            combined[tag] = {"status": "NOT_COMBINED (an app is missing/reset for this series; report per-app)"}; continue
        if set(e1["_bucket_delta"]) != set(e2["_bucket_delta"]):
            combined[tag] = {"status": "NOT_COMBINED (bucket sets differ)"}; continue
        bd = {le: e1["_bucket_delta"][le] + e2["_bucket_delta"][le] for le in e1["_bucket_delta"]}
        c, s = e1["count_delta"] + e2["count_delta"], e1["sum_s_delta"] + e2["sum_s_delta"]
        sts = (e1["status"], e2["status"])
        cs = "CONDITIONAL_MANUAL_INFERENCE(bucket-summed)" if "CONDITIONAL_MANUAL_INFERENCE" in sts else ("OK_INFERRED_ZERO_BASELINE(bucket-summed)" if "OK_INFERRED_ZERO_BASELINE" in sts else "OK(bucket-summed)")
        combined[tag] = {"status": cs, "count_delta": c, "mean_ms": round(1000 * s / c, 3) if c else None,
                         "quantile_upper_le_s": {"p50": upper_le_for_quantile(bd, .5), "p95": upper_le_for_quantile(bd, .95), "p99": upper_le_for_quantile(bd, .99)}}
    for app in per_app.values():
        for ent in app.values():
            if isinstance(ent, dict): ent.pop("_bucket_delta", None)
    return {"per_app": per_app, "combined": combined}

acq = summarize("coupon_redis_lock_acquire"); hold = summarize("coupon_redis_lock_hold")

# 미계측 구간 대조: 락 타이머 acquire count(모든 outcome) vs 서버가 처리한 loadtest 요청 수
def http_count(app):
    """반환 (delta|None, before_series_absent, status). before 에 해당 uri 시리즈가 없을 때는 timer 와 동일하게 family 허용 검사(http_server_requests)를 거친다."""
    b, a = load(run_dir / f"{app}-before.prom"), load(run_dir / f"{app}-after.prom")
    if b is None or a is None: return None, False, "MISSING"
    def sel(rows): return [v for n, lab, v in rows if n == "http_server_requests_seconds_count" and lab.get("uri", "").startswith("/internal/loadtest/coupons")]
    if sel(b): return sum(sel(a)) - sum(sel(b)), False, "OK"
    if not sel(a): return None, True, "MISSING"   # after 에도 없음: 요청 0 인지 노출 누락인지 구별 불가
    st, why = PB.lazy_zero_allowed(evidence_for(app), "http_server_requests", ALLOW_MANUAL)
    return (sum(sel(a)), True, st) if st else (None, True, "MISSING")
recon = {}
for app in ("app1", "app2"):
    h, h_absent, h_status = http_count(app)
    acquired = acq["per_app"].get(app, {})
    okents = [e for t, e in acquired.items() if isinstance(e, dict) and usable(e.get("status", ""))]
    c = sum(e["count_delta"] for e in okents) if acquired else None
    timer_inferred = [t for t, e in acquired.items() if isinstance(e, dict) and e.get("status") in ("OK_INFERRED_ZERO_BASELINE", "CONDITIONAL_MANUAL_INFERENCE")]
    sites_ok, sites = PB.lock_call_sites_evidenced(PB.read_provenance(run_dir / f"{app}-scrape-provenance.json"))
    log = run_dir / f"{app}.log"
    log_counts = None
    if log.exists():
        txt = log.read_text(errors="replace")
        log_counts = {"LockAcquisitionFailedException": txt.count("LockAcquisitionFailedException"), "Lock acquisition interrupted": txt.count("Lock acquisition interrupted"),
                      "ERROR_lines": len(re.findall(r"\bERROR\b", txt)), "RedisException/timeout lines": len(re.findall(r"Redis[A-Za-z]*Exception|RedisTimeout", txt))}
    gap = (h - c) if (h is not None and c is not None) else None
    # 대조는 '락 timer count 가 loadtest 요청만 센다'는 전제가 필요하다: worker/recovery 락 호출 경로 비활성 근거가 없으면 UNKNOWN
    verdict = gap if (gap is not None and sites_ok) else "UNKNOWN"
    recon[app] = {"http_loadtest_requests_delta": h, "http_before_series_absent": h_absent, "http_baseline_status": h_status, "lock_acquire_timer_count_all_outcomes": c,
                  "depends_on_inferred_zero_baseline": {"timer_series": timer_inferred, "http_before_series_absent": h_absent,
                                                        "any": bool(timer_inferred) or h_absent,
                                                        "note": "true 면 untimed 추정은 직접 관측이 아니라 추론(또는 조건부 수동 추론)에 근거한 값"},
                  "lock_call_sites_evidence": {"verified": sites_ok, "recorded": sites,
                                               "scope": "락 timer(coupon.redis_lock.*)는 worker/recovery 등 모든 @DistributedLock 사이트를 포함한다. loadtest URI 요청만 센 http 와 대조하려면 다른 사이트 비활성 근거가 필요하다"},
                  "untimed_estimate(http - timer, NOT assumed zero)": verdict,
                  "gap_numeric_diagnostic_only(not a conclusion)": gap,
                  "app_log_counts": log_counts if log_counts is not None else "MISSING (app log not captured)"}
summary = {"acquire": acq, "hold": hold, "untimed_reconciliation": recon,
           "notes": ["hold wraps the whole REQUIRES_NEW transaction (DB connection acquire..commit) - not pure Redis time",
                     "default wait 5s / lease 3s (DistributedLock annotation defaults); see lock-annotation-sites.txt for per-site config",
                     "same metric names are used by the async worker and recovery worker sites; metrics carry no method tag -> separate by running with COUPON_ISSUE_WORKER_MODE=off and checking worker logs",
                     "non-interrupt tryLock exceptions are NOT timed; compare with http 5xx/log counts, do not treat the gap as zero"]}
(out / "lock-metrics.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
print(json.dumps(summary, indent=2, ensure_ascii=False))
