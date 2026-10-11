#!/usr/bin/env python3
"""v1-smoke 서버측 묶음 접수 지표를 prom_baseline 규칙으로 재계산(원본 읽기 전용; DB/docker 불필요).
사용: PYTHONPATH=<repo>/scripts/verifier-isolated python3 reanalyze-v1-server-metrics.py <v1-smoke/run> [--allow-manual-inference]"""
import json, pathlib, sys
import prom_baseline as PB
allow = "--allow-manual-inference" in sys.argv
rd = pathlib.Path([x for x in sys.argv[1:] if not x.startswith("--")][0]); out = {}
def ssum(rows, n):
    v = [x for m, l, x in rows if m == n]; return sum(v) if v else None
for app in ("app1", "app2"):
    bp, ap = rd / f"{app}-before.prom", rd / f"{app}-after.prom"
    br, ar = PB.read_prom(bp), PB.read_prom(ap)
    ev = PB.scrape_evidence(br, ar, PB.read_provenance(rd / f"{app}-scrape-provenance.json"), (bp, ap))
    out[app] = {"structural_ok": ev["structural_ok"], "provenance_ok": ev["provenance_ok"], "structural_checks": ev["checks"], "provenance_checks": ev["provenance_checks"]}
    for key, fam in (("batch_queue_wait", "coupon_admission_batch_queue_wait"), ("admission_tx", "coupon_admission_transaction")):
        c1, s1 = ssum(ar["rows"], fam + "_seconds_count"), ssum(ar["rows"], fam + "_seconds_sum")
        c0, s0 = ssum(br["rows"], fam + "_seconds_count"), ssum(br["rows"], fam + "_seconds_sum")
        absent = c0 is None and s0 is None
        st, why = PB.lazy_zero_allowed(ev, fam, allow) if absent else ("OK", "direct")
        if c1 and st:
            c0, s0 = c0 or 0, s0 or 0
            out[app][key] = {"status": st, "count_delta": c1 - c0, "sum_s_delta": round(s1 - s0, 6), "mean_ms": round(1000 * (s1 - s0) / (c1 - c0), 2), "basis": why}
        else: out[app][key] = {"status": "MISSING", "basis": why}
print(json.dumps(out, indent=2, ensure_ascii=False))
