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

def run(files, logs=None):
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t)
        for k, v in files.items(): (t / k).write_text(v)
        for k, v in (logs or {}).items(): (t / k).write_text(v)
        p = subprocess.run([sys.executable, "-B", str(HERE / "analyze-lock-metrics.py"), str(t), str(t / "o")], capture_output=True, text=True)
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
        self.assertEqual(r["untimed_estimate(http - timer, NOT assumed zero)"], 10)
        self.assertEqual(r["app_log_counts"]["RedisException/timeout lines"], 1)
        self.assertTrue(s["untimed_reconciliation"]["app2"]["app_log_counts"].startswith("MISSING"))
if __name__ == "__main__": unittest.main()
