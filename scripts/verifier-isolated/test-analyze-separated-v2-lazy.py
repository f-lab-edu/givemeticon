#!/usr/bin/env python3
"""analyze-separated-v2.py 서버측 지표의 absent-before 처리 테스트(docker 는 가짜 shim, 합성 입력)."""
import hashlib, json, os, pathlib, stat, subprocess, sys, tempfile, unittest
HERE = pathlib.Path(__file__).parent
PROC = 'process_start_time_seconds{application="g",} 1.7E9\nprocess_uptime_seconds{application="g",} %s\njvm_info{application="g",version="17",} 1.0\n'
TARGET = "http://127.0.0.1:8081/actuator/prometheus"
HEALTH = 'http_server_requests_seconds_count{application="g",uri="/actuator/health",} 2\n'
BATCH = ('coupon_admission_batch_queue_wait_seconds_count{application="g",} 4\ncoupon_admission_batch_queue_wait_seconds_sum{application="g",} 2.0\n'
         'coupon_admission_transaction_seconds_count{application="g",} 4\ncoupon_admission_transaction_seconds_sum{application="g",} 1.2\n')

def prov(before, after, policy=True, paths=None):
    h = lambda t: hashlib.sha256(t.encode()).hexdigest()
    d = {"schema": "scrape-provenance/v1", "expected_target": TARGET, "scrapes": {
        "before": {"exit_code": 0, "http_status": 200, "requested_target": TARGET, "effective_target": TARGET, "body_bytes": len(before.encode()), "body_sha256": h(before)},
        "after": {"exit_code": 0, "http_status": 200, "requested_target": TARGET, "effective_target": TARGET, "body_bytes": len(after.encode()), "body_sha256": h(after)}}}
    if policy: d["meter_policy"] = {"custom_app_meter_filter_or_registry_remove_used": False, "framework_meter_filters": ["OnlyOnceLoggingDenyMeterFilter(max-uri-tags)", "PropertiesMeterFilter"], "effective_metrics_config": {"max_uri_tags": 100, "enable": {}, "source_sha256": "abc"}, "evidence": "git grep: none", "spring_boot": "3.1.3", "micrometer": "1.11.x", "registration_paths": paths if paths is not None else {"coupon_admission_batch_queue_wait": "CouponBatchAdmissionAcceptor:175", "coupon_admission_transaction": "CouponAdmissionTransactionTimers"}}
    return json.dumps(d)

def run(before, after, with_prov=True, extra=(), policy=True, paths=None):
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t); rd = t / "run"; rd.mkdir(); bindir = t / "bin"; bindir.mkdir()
        sh = bindir / "docker"; sh.write_text("#!/bin/sh\nexit 0\n"); sh.chmod(sh.stat().st_mode | stat.S_IXUSR)
        (rd / "k6-summary.json").write_text(json.dumps({"metrics": {"iterations": {"count": 0}, "admission_attempts": {"count": 0}}}))
        for a in ("app1", "app2"):
            if before is not None: (rd / f"{a}-before.prom").write_text(before)
            (rd / f"{a}-after.prom").write_text(after)
            if with_prov and before is not None: (rd / f"{a}-scrape-provenance.json").write_text(prov(before, after, policy, paths))
        env = dict(os.environ, PATH=f"{bindir}:{os.environ['PATH']}")
        p = subprocess.run([sys.executable, "-B", str(HERE / "analyze-separated-v2.py"), str(rd), str(t / "o"), "c", "db", "1", "1", *extra], capture_output=True, text=True, env=env)
        assert p.returncode == 0, p.stderr
        return json.loads(p.stdout)["server_side_admission_metrics_run_delta"]

class T(unittest.TestCase):
    def test_lazy_registered_is_inferred_and_labeled(self):
        r = run((PROC % 40) + HEALTH, (PROC % 60) + HEALTH + BATCH)["app1"]["batch_queue_wait"]
        self.assertEqual(r["status"], "OK_INFERRED_ZERO_BASELINE"); self.assertEqual(r["count_delta"], 4); self.assertEqual(r["mean_ms"], 500.0)
        self.assertIn("not directly observed", r["baseline"])
    def test_no_provenance_missing_manual_flag_conditional(self):
        b, a = (PROC % 40) + HEALTH, (PROC % 60) + HEALTH + BATCH
        self.assertEqual(run(b, a, with_prov=False)["app1"]["batch_queue_wait"]["status"], "MISSING")
        self.assertEqual(run(b, a, with_prov=False, extra=("--allow-manual-inference",))["app1"]["batch_queue_wait"]["status"], "CONDITIONAL_MANUAL_INFERENCE")
        self.assertEqual(run(b, a, policy=False)["app1"]["batch_queue_wait"]["status"], "MISSING")
    def test_registration_path_must_be_for_the_requested_family(self):
        b, a = (PROC % 40) + HEALTH, (PROC % 60) + HEALTH + BATCH
        r = run(b, a, paths={"coupon_admission_transaction": "ref"})   # queue_wait 자신의 경로 없음, 다른 family 경로만 있음
        self.assertEqual(r["app1"]["batch_queue_wait"]["status"], "MISSING"); self.assertEqual(r["app1"]["admission_tx"]["status"], "OK_INFERRED_ZERO_BASELINE")
    def test_direct_observation_stays_direct(self):
        b = (PROC % 40) + HEALTH + BATCH.replace("} 4", "} 1").replace("} 2.0", "} 0.5").replace("} 1.2", "} 0.3")
        r = run(b, (PROC % 60) + HEALTH + BATCH)["app1"]["admission_tx"]
        self.assertEqual(r["status"], "OK"); self.assertEqual(r["baseline"], "direct"); self.assertEqual(r["count_delta"], 3)
    def test_restart_or_missing_before_stays_missing(self):
        self.assertEqual(run((PROC % 40) + HEALTH, (PROC % 60).replace("1.7E9", "1.8E9") + HEALTH + BATCH)["app1"]["batch_queue_wait"]["status"], "MISSING")
        self.assertEqual(run(None, (PROC % 60) + HEALTH + BATCH)["app1"]["batch_queue_wait"]["status"], "MISSING")
    def test_reset_is_not_zero(self):
        b = (PROC % 40) + HEALTH + BATCH.replace("} 4", "} 9")
        self.assertEqual(run(b, (PROC % 60) + HEALTH + BATCH)["app1"]["admission_tx"]["status"], "RESET")
if __name__ == "__main__": unittest.main()
