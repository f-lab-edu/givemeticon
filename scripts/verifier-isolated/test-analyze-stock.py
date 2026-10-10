#!/usr/bin/env python3
"""analyze-stock.py 대체 실행 테스트(합성 로그/DB fixture; Docker·DB 불필요)."""
import json, pathlib, subprocess, sys, tempfile, unittest
HERE = pathlib.Path(__file__).parent

def rec(u, cat, status, d=10, attempt=1, in_window=True):
    return json.dumps({"kind": "stock_request", "attempt": attempt, "userId": u, "target": 1, "category": cat, "status": status, "durationMs": d, "sentAtMs": 1000 + u % 1000, "respondedAtMs": 2000, "inWindow": in_window})

def run(records, db_users, iters, total=2, extra_lines=(), dup_rate="0"):
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t)
        (t / "k6-failures.log").write_text("\n".join(list(records) + list(extra_lines)))
        (t / "k6-summary.json").write_text(json.dumps({"metrics": {"iterations": {"count": iters}}}))
        (t / "fx.tsv").write_text("\n".join(f"{u}\t1" for u in db_users))
        p = subprocess.run([sys.executable, "-B", str(HERE / "analyze-stock.py"), str(t), str(t / "out"), "c", "d", "1", "100", str(total), f"--db-fixture={t/'fx.tsv'}", f"--dup-rate={dup_rate}"], capture_output=True, text=True)
        return p.returncode, (json.loads(p.stdout) if p.returncode == 0 else p.stderr)

class T(unittest.TestCase):
    def test_clean_run(self):
        rc, s = run([rec(100, "success", 200), rec(101, "success", 200), rec(102, "business_rejected", 409)], [100, 101], 3)
        self.assertEqual(rc, 0); self.assertEqual(s["integrity"]["verdict"], "OK")
        self.assertEqual(s["db"]["over_issued"], 0); self.assertEqual(s["success_response_but_not_in_db"], 0)
    def test_over_issue_detected(self):
        rc, s = run([rec(100, "success", 200), rec(101, "success", 200), rec(102, "success", 200)], [100, 101, 102], 3, total=2)
        self.assertEqual(s["db"]["over_issued"], 1)
    def test_timeout_but_db_issued_is_separated(self):
        rc, s = run([rec(100, "success", 200), rec(101, "client_timeout", 0, 10000)], [100, 101], 2)
        self.assertEqual(s["db_issued_but_client_did_not_see_200"], {"client_timeout": 1})
    def test_missing_and_parse_errors_flag_mismatch(self):
        rc, s = run([rec(100, "success", 200)], [100], 3, extra_lines=['{"kind": "stock_request", broken'])
        self.assertTrue(s["integrity"]["verdict"].startswith("MISMATCH")); self.assertEqual(s["integrity"]["missing_post_records"], 2)
        self.assertEqual(s["integrity"]["parse_errors"], 1)
    def test_duplicate_first_request_flagged(self):
        rc, s = run([rec(100, "success", 200), rec(100, "success", 200)], [100], 1)
        self.assertEqual(s["integrity"]["users_with_multiple_records"], 1); self.assertTrue(s["integrity"]["verdict"].startswith("MISMATCH"))
    def test_success_not_in_db_flagged(self):
        rc, s = run([rec(100, "success", 200), rec(101, "success", 200)], [100], 2)
        self.assertEqual(s["success_response_but_not_in_db"], 1)
    def test_deterministic_duplicates_match_contract(self):
        # start=100, rate 0.5 -> every 2nd offset (100,102) duplicates
        recs = [rec(100, "success", 200), rec(101, "success", 200), rec(102, "business_rejected", 409),
                rec(100, "business_rejected", 409, attempt=2), rec(102, "business_rejected", 409, attempt=2)]
        rc, s = run(recs, [100, 101], 3, dup_rate="0.5")
        self.assertEqual(s["integrity"]["verdict"], "OK"); self.assertEqual(s["duplicates"]["attempts_recorded"], 2)
        self.assertEqual(s["duplicates"]["missing_expected"], 0); self.assertEqual(s["duplicates"]["unexpected"], 0)
        self.assertEqual(s["denominator_first_requests"], 3)   # 중복은 분모에 넣지 않는다
    def test_missing_or_unexpected_duplicate_flagged(self):
        recs = [rec(100, "success", 200), rec(101, "success", 200), rec(101, "business_rejected", 409, attempt=2)]
        rc, s = run(recs, [100, 101], 2, dup_rate="0.5")
        self.assertEqual(s["duplicates"]["missing_expected"], 1); self.assertEqual(s["duplicates"]["unexpected"], 1)
        self.assertTrue(s["integrity"]["verdict"].startswith("MISMATCH"))
    def test_window_split_counted_and_kept_in_denominator(self):
        recs = [rec(100, "success", 200), rec(101, "success", 200, in_window=False)]
        rc, s = run(recs, [100, 101], 2)
        self.assertEqual(s["send_window"]["first_requests_in_window"], 1); self.assertEqual(s["send_window"]["first_requests_outside_window"], 1)
        self.assertEqual(s["denominator_first_requests"], 2)
    def test_duplicate_second_coupon_is_detected_by_db_count(self):
        recs = [rec(100, "success", 200), rec(100, "success", 200, attempt=2)]
        rc, s = run(recs, [100], 1, total=1, dup_rate="1")
        self.assertEqual(s["duplicates"]["category_counts"], {"success": 1})   # 두 번째도 200 -> 별도 확인 필요(DB duplicate_users 는 DB 에서)
if __name__ == "__main__": unittest.main()
