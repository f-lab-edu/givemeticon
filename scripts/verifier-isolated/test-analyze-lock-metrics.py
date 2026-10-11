#!/usr/bin/env python3
"""analyze-lock-metrics.py 대체 실행 테스트(합성 .prom)."""
import json, pathlib, subprocess, sys, tempfile, unittest
HERE = pathlib.Path(__file__).parent

def prom(acq_acquired, acq_rejected, hold, http, acq_les=("0.1", "1.0", "+Inf"), extra=""):
    """acq_acquired: (count,sum,[bucket cumulative per le])"""
    L = []
    def series(name, tags, count, s, buckets, les):
        t = (tags + ",") if tags else ""
        for le, v in zip(les, buckets): L.append(f'{name}_seconds_bucket{{application="g",{t}le="{le}",}} {v}')
        L.append(f'{name}_seconds_count{{application="g",{tags}}} {count}'); L.append(f'{name}_seconds_sum{{application="g",{tags}}} {s}')
    if acq_acquired is not None: series("coupon_redis_lock_acquire", 'outcome="acquired"', acq_acquired[0], acq_acquired[1], acq_acquired[2], acq_les)
    if acq_rejected is not None: series("coupon_redis_lock_acquire", 'outcome="rejected"', acq_rejected[0], acq_rejected[1], acq_rejected[2], acq_les)
    if hold is not None: series("coupon_redis_lock_hold", "", hold[0], hold[1], hold[2], ("0.1", "1.0", "+Inf"))
    L.append(f'http_server_requests_seconds_count{{application="g",uri="/internal/loadtest/coupons",}} {http}')
    return "\n".join(L) + "\n" + extra

def run(files, logs=None, extra=()):
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t)
        for k, v in files.items(): (t / k).write_text(v)
        for k, v in (logs or {}).items(): (t / k).write_text(v)
        p = subprocess.run([sys.executable, "-B", str(HERE / "analyze-lock-metrics.py"), str(t), str(t / "o"), *extra], capture_output=True, text=True)
        return json.loads(p.stdout)

B = prom((0, 0, [0, 0, 0]), None, (0, 0, [0, 0, 0]), 0)
class T(unittest.TestCase):
    def files(self, a1, a2, b2=B):
        return {"app1-before.prom": B, "app1-after.prom": a1, "app2-before.prom": b2, "app2-after.prom": a2}
    def test_per_app_and_bucket_summed_not_averaged_quantiles(self):
        a1 = prom((100, 10.0, [50, 99, 100]), (5, 25.0, [0, 0, 5]), (100, 30.0, [10, 90, 100]), 110)
        a2 = prom((100, 40.0, [10, 60, 100]), None, (100, 90.0, [0, 20, 100]), 100)
        s = run(self.files(a1, a2))
        c = s["acquire"]["combined"]["outcome=acquired"]
        self.assertTrue(c["status"].startswith("OK")); self.assertEqual(c["count_delta"], 200); self.assertEqual(c["mean_ms"], 250.0)   # (10+40)/200
        self.assertEqual(c["quantile_upper_le_s"]["p50"], "1.0")   # 합산 누적 [60,159,200]: p50(100) -> le 1.0
        self.assertEqual(s["acquire"]["per_app"]["app1"]["outcome=acquired"]["quantile_upper_le_s"]["p50"], "0.1")   # 앱별은 다르게 보고
        self.assertEqual(s["hold"]["per_app"]["app2"]["(no-tags)"]["mean_ms"], 900.0)
    def test_reset_is_distinguished_from_zero(self):
        after_reset = prom((3, 0.3, [3, 3, 3]), None, (3, 0.3, [3, 3, 3]), 3)
        before_high = prom((100, 10.0, [50, 99, 100]), None, (100, 30.0, [10, 90, 100]), 100)
        s = run(self.files(after_reset, after_reset, b2=before_high))
        self.assertEqual(s["acquire"]["per_app"]["app2"]["outcome=acquired"]["status"], "RESET")
        self.assertTrue(s["acquire"]["combined"]["outcome=acquired"]["status"].startswith("NOT_COMBINED"))
    def test_missing_metric_and_file_not_zero(self):
        a = prom(None, None, None, 5)
        s = run(self.files(a, a))
        self.assertEqual(s["acquire"]["per_app"]["app1"]["outcome=acquired"]["status"], "MISSING")   # after 에서 시리즈가 사라짐 -> 0 이 아니라 MISSING
        s2 = run({"app1-before.prom": B, "app1-after.prom": B})
        self.assertTrue(s2["acquire"]["per_app"]["app2"]["status"].startswith("MISSING"))
    def test_bucket_set_mismatch_not_combined(self):
        a1 = prom((10, 1.0, [5, 9, 10]), None, (10, 1.0, [5, 9, 10]), 10)
        a2 = prom((10, 1.0, [5, 10]), None, (10, 1.0, [5, 9, 10]), 10, acq_les=("0.5", "+Inf"))
        s = run(self.files(a1, a2))
        self.assertEqual(s["acquire"]["combined"]["outcome=acquired"]["status"], "NOT_COMBINED (bucket sets differ)")
    def test_untimed_gap_reported_not_assumed_zero(self):
        a1 = prom((90, 9.0, [90, 90, 90]), None, (90, 9.0, [90, 90, 90]), 100)   # http 100 vs timer 90 -> gap 10
        s = run(self.files(a1, a1), logs={"app1.log": "x\nERROR RedisTimeoutException boom\nERROR other\n"})
        r = s["untimed_reconciliation"]["app1"]
        self.assertEqual(r["untimed_estimate(http - timer, NOT assumed zero)"], "UNKNOWN")   # 락 사이트 비활성 근거(provenance) 없음 -> 갭을 결론으로 쓰지 않음
        self.assertEqual(r["gap_numeric_diagnostic_only(not a conclusion)"], 10)
        self.assertEqual(r["app_log_counts"]["RedisException/timeout lines"], 1)
        self.assertTrue(s["untimed_reconciliation"]["app2"]["app_log_counts"].startswith("MISSING"))

# ---- absent-before(지연 등록 meter) 추론 테스트: 본문 검사 + 수집 시점 provenance + 등록 경로가 모두 있을 때만 inferred_zero_baseline ----
import hashlib
PROC = 'process_start_time_seconds{application="g",} 1.7E9\nprocess_uptime_seconds{application="g",} %s\njvm_info{application="g",version="17",} 1.0\n'
HEALTH = 'http_server_requests_seconds_count{application="g",uri="/actuator/health",} 2\n'
TARGET = "http://127.0.0.1:8081/actuator/prometheus"
def base(uptime=40, start="1.7E9", health=HEALTH, extra=""):
    return (PROC.replace("1.7E9", start) % uptime) + health + extra
def after_with_lock(uptime=60, start="1.7E9", http=5):
    p = prom((3, 0.3, [3, 3, 3]), (2, 10.0, [0, 0, 2]), (3, 0.3, [3, 3, 3]), http)
    return (PROC.replace("1.7E9", start) % uptime) + HEALTH + p
def sha(t): return hashlib.sha256(t.encode()).hexdigest()
ALL_PATHS = {f: "reviewed source ref for " + f for f in ("coupon_redis_lock_acquire", "coupon_redis_lock_hold", "http_server_requests", "coupon_admission_transaction")}
def prov(before, after, exit_b=0, target=TARGET, policy=True, filt_used=False, sites=True, schema="scrape-provenance/v1", paths=None, requested=TARGET):
    d = {"schema": schema, "expected_target": TARGET,
         "scrapes": {"before": {"exit_code": exit_b, "http_status": 200, "requested_target": requested, "effective_target": target, "body_bytes": len(before.encode()), "body_sha256": sha(before)},
                     "after": {"exit_code": 0, "http_status": 200, "requested_target": TARGET, "effective_target": TARGET, "body_bytes": len(after.encode()), "body_sha256": sha(after)}}}
    if policy: d["meter_policy"] = {"meter_filter_or_registry_remove_used": filt_used, "evidence": "git grep MeterFilter|remove @b1e08f7: none; application.yml sha256=abc",
                                    "spring_boot": "3.1.3", "micrometer": "1.11.x", "registration_paths": ALL_PATHS if paths is None else paths}
    # 주의: 아래 evidence 문구는 형식 검증용 예시이며 실제 stock runner 설정을 대표하지 않는다(실제 runner 는 recovery scheduler 를 끄지 않는다 - Reviewer 지적).
    if sites: d["lock_call_sites"] = {"worker_disabled": True, "recovery_disabled": True, "evidence": "COUPON_ISSUE_WORKER_MODE=off, recovery scheduler off (run-meta)"}
    return json.dumps(d)
class Lazy(unittest.TestCase):
    def run_lazy(self, before, after, with_prov=True, extra=(), **kw):
        files = {"app1-before.prom": before, "app1-after.prom": after, "app2-before.prom": before, "app2-after.prom": after}
        if with_prov:
            for a in ("app1", "app2"): files[f"{a}-scrape-provenance.json"] = prov(kw.pop("prov_before", before) if False else kw.get("prov_before", before), kw.get("prov_after", after), **{k: v for k, v in kw.items() if k not in ("prov_before", "prov_after")})
        return run(files, extra=extra)
    def acq(self, s, outcome="acquired"): return s["acquire"]["per_app"]["app1"][f"outcome={outcome}"]
    def test_normal_lazy_with_provenance_is_inferred_and_reconciles(self):
        s = self.run_lazy(base(), after_with_lock())
        e = self.acq(s); self.assertEqual(e["status"], "OK_INFERRED_ZERO_BASELINE"); self.assertEqual(e["count_delta"], 3)
        self.assertTrue(e["absent_before"]["inferred_zero_baseline"])
        r = s["untimed_reconciliation"]["app1"]
        self.assertEqual(r["untimed_estimate(http - timer, NOT assumed zero)"], 0)   # 5 - (3+2), 락 사이트 비활성 근거 있음
        self.assertTrue(r["depends_on_inferred_zero_baseline"]["any"]); self.assertTrue(r["lock_call_sites_evidence"]["verified"])
        self.assertTrue(s["acquire"]["combined"]["outcome=acquired"]["status"].startswith("OK_INFERRED"))
    def test_no_provenance_is_missing_unless_manual_flag_then_conditional(self):
        s = self.run_lazy(base(), after_with_lock(), with_prov=False)
        self.assertEqual(self.acq(s)["status"], "MISSING")
        self.assertEqual(s["untimed_reconciliation"]["app1"]["untimed_estimate(http - timer, NOT assumed zero)"], "UNKNOWN")
        m = self.run_lazy(base(), after_with_lock(), with_prov=False, extra=("--allow-manual-inference",))
        e = self.acq(m); self.assertEqual(e["status"], "CONDITIONAL_MANUAL_INFERENCE"); self.assertTrue(e["absent_before"]["conditional_manual"]); self.assertFalse(e["absent_before"]["inferred_zero_baseline"])
        self.assertFalse(e["status"].startswith("OK"))
        self.assertEqual(m["untimed_reconciliation"]["app1"]["untimed_estimate(http - timer, NOT assumed zero)"], "UNKNOWN")   # 락 사이트 근거 없음 -> 수치가 같아도 확정 안 함
        self.assertEqual(m["untimed_reconciliation"]["app1"]["gap_numeric_diagnostic_only(not a conclusion)"], 0)
    def test_warmed_before_with_activity_new_rejected_tag_is_inferred_positive(self):
        warmed = base(health=HEALTH + 'http_server_requests_seconds_count{application="g",uri="/internal/loadtest/coupons",} 7\n',
                      extra='coupon_redis_lock_acquire_seconds_count{application="g",outcome="acquired",} 7\ncoupon_redis_lock_acquire_seconds_sum{application="g",outcome="acquired",} 0.7\ncoupon_redis_lock_acquire_seconds_bucket{application="g",outcome="acquired",le="+Inf",} 7\n')
        after = (PROC % 60) + HEALTH + 'http_server_requests_seconds_count{application="g",uri="/internal/loadtest/coupons",} 19\n' + \
            'coupon_redis_lock_acquire_seconds_count{application="g",outcome="acquired",} 14\ncoupon_redis_lock_acquire_seconds_sum{application="g",outcome="acquired",} 1.4\ncoupon_redis_lock_acquire_seconds_bucket{application="g",outcome="acquired",le="+Inf",} 14\n' + \
            'coupon_redis_lock_acquire_seconds_count{application="g",outcome="rejected",} 5\ncoupon_redis_lock_acquire_seconds_sum{application="g",outcome="rejected",} 25.0\ncoupon_redis_lock_acquire_seconds_bucket{application="g",outcome="rejected",le="+Inf",} 5\n'
        s = self.run_lazy(warmed, after)
        self.assertEqual(self.acq(s)["status"], "OK"); self.assertEqual(self.acq(s)["count_delta"], 7)   # 직접 관측
        r = self.acq(s, "rejected"); self.assertEqual(r["status"], "OK_INFERRED_ZERO_BASELINE"); self.assertEqual(r["count_delta"], 5)   # 예열 활동이 있어도 새 태그는 추론
        self.assertEqual(r["absent_before"]["diagnostics"]["non_actuator_http_count_before"], 7)   # 진단 필드로만 남는다
        rc = s["untimed_reconciliation"]["app1"]; self.assertFalse(rc["http_before_series_absent"]); self.assertEqual(rc["untimed_estimate(http - timer, NOT assumed zero)"], 12 - 12)
    def test_provenance_failures_keep_missing(self):
        b, a = base(), after_with_lock()
        for name, kw in (("exit code", dict(exit_b=28)), ("target mismatch", dict(target="http://127.0.0.1:9999/actuator/prometheus")),
                         ("no meter policy", dict(policy=False)), ("filter used", dict(filt_used=True))):
            with self.subTest(name): self.assertEqual(self.acq(self.run_lazy(b, a, **kw))["status"], "MISSING")
    def test_schema_and_family_specific_registration_evidence_required(self):
        b, a = base(), after_with_lock()
        with self.subTest("unsupported schema"): self.assertEqual(self.acq(self.run_lazy(b, a, schema="scrape-provenance/v0"))["status"], "MISSING")
        with self.subTest("path for another family only"): self.assertEqual(self.acq(self.run_lazy(b, a, paths={"coupon_redis_lock_hold": "x"}))["status"], "MISSING")
        with self.subTest("blank path"): self.assertEqual(self.acq(self.run_lazy(b, a, paths={"coupon_redis_lock_acquire": "  "}))["status"], "MISSING")
        with self.subTest("exact family path ok"): self.assertEqual(self.acq(self.run_lazy(b, a, paths={"coupon_redis_lock_acquire": "ref", "coupon_redis_lock_hold": "ref", "http_server_requests": "ref"}))["status"], "OK_INFERRED_ZERO_BASELINE")
        with self.subTest("http family without its own path"):
            s = self.run_lazy(b, a, paths={"coupon_redis_lock_acquire": "ref"})
            self.assertEqual(s["untimed_reconciliation"]["app1"]["http_baseline_status"], "MISSING")
    def test_requested_target_must_match_run_contract(self):
        s = self.run_lazy(base(), after_with_lock(), requested="http://127.0.0.1:9999/actuator/prometheus")   # redirect/잘못된 앱 포트
        self.assertEqual(self.acq(s)["status"], "MISSING")
    def test_truncated_body_hash_mismatch_keeps_missing(self):
        full = base() + "extra_series_total 1\n"
        s = self.run_lazy(base(), after_with_lock(), prov_before=full)   # 기록된 해시/크기는 완전한 본문, 실제 파일은 줄 경계에서 잘림
        self.assertEqual(self.acq(s)["status"], "MISSING")
    def test_valid_partial_response_with_only_process_lines_keeps_missing(self):
        partial = (PROC % 40)   # 문법상 유효하고 process/uptime 만 있으나 jvm_info 를 제외한 나머지가 없는 응답: 해시는 맞아도 앵커/노출 검사 실패해야 한다
        partial = "\n".join(l for l in partial.splitlines() if not l.startswith("jvm_info")) + "\n"
        self.assertEqual(self.acq(self.run_lazy(partial, after_with_lock()))["status"], "MISSING")
    def test_http_series_selectively_removed_without_policy_is_missing(self):
        no_http_after = after_with_lock().replace(HEALTH, "")
        s = self.run_lazy(base(), no_http_after, policy=False)   # 선택적 제거 가능성을 배제할 등록/필터 정책 근거 없음
        self.assertEqual(self.acq(s)["status"], "MISSING")
        self.assertEqual(s["untimed_reconciliation"]["app1"]["untimed_estimate(http - timer, NOT assumed zero)"], "UNKNOWN")
    def test_http_absent_before_goes_through_family_gate(self):
        s = self.run_lazy(base(), after_with_lock(), policy=False)
        self.assertEqual(s["untimed_reconciliation"]["app1"]["http_baseline_status"], "MISSING"); self.assertIsNone(s["untimed_reconciliation"]["app1"]["http_loadtest_requests_delta"])
    def test_sites_not_evidenced_makes_reconciliation_unknown_even_if_equal(self):
        s = self.run_lazy(base(), after_with_lock(), sites=False)
        r = s["untimed_reconciliation"]["app1"]; self.assertEqual(r["untimed_estimate(http - timer, NOT assumed zero)"], "UNKNOWN"); self.assertEqual(r["gap_numeric_diagnostic_only(not a conclusion)"], 0)
        self.assertFalse(r["lock_call_sites_evidence"]["verified"])
    def test_structural_failures_keep_missing_even_with_provenance(self):
        cases = {"restart": (base(), after_with_lock(start="1.8E9")), "uptime regress": (base(uptime=90), after_with_lock(uptime=60)),
                 "counter reset": (base(extra='jvm_gc_pause_seconds_count{application="g",} 50\n'), after_with_lock() + 'jvm_gc_pause_seconds_count{application="g",} 3\n'),
                 "series vanished": (base(extra='some_other_total{application="g",} 4\n'), after_with_lock()),
                 "different target label": (base().replace('application="g"', 'application="other"'), after_with_lock()),
                 "empty before": ("", after_with_lock())}
        for name, (b, a) in cases.items():
            with self.subTest(name): self.assertEqual(self.acq(self.run_lazy(b, a))["status"], "MISSING")
    def test_missing_before_file_or_garbage(self):
        s = run({"app1-before.prom": "garbage line without value\n", "app1-after.prom": after_with_lock(), "app2-after.prom": after_with_lock()})
        self.assertEqual(self.acq(s)["status"], "MISSING"); self.assertTrue(s["acquire"]["per_app"]["app2"]["status"].startswith("MISSING"))
    def test_long_task_timer_decrease_and_brace_in_label(self):
        before = base(extra='http_server_requests_active_seconds_duration_sum{application="g",} 5.0\n')
        after = after_with_lock() + 'http_server_requests_active_seconds_duration_sum{application="g",} 0.1\nhttp_server_requests_seconds_bucket{application="g",uri="/x/{eventId}/me",le="0.1",} 3\n'
        self.assertEqual(self.acq(self.run_lazy(before, after))["status"], "OK_INFERRED_ZERO_BASELINE")
    def test_non_lazy_family_never_inferred(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("pb", HERE / "prom_baseline.py"); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
        ev = {"structural_ok": True, "provenance_ok": True, "checks": {}, "provenance_checks": {"x": {"pass": True, "detail": ""}}, "registration_paths": ALL_PATHS}
        self.assertIsNone(m.lazy_zero_allowed(ev, "jvm_gc_pause", True)[0])
        self.assertEqual(m.lazy_zero_allowed(ev, "coupon_redis_lock_hold")[0], "OK_INFERRED_ZERO_BASELINE")
        self.assertEqual(m.lazy_zero_allowed(ev, "http_server_requests")[0], "OK_INFERRED_ZERO_BASELINE")
if __name__ == "__main__": unittest.main()
