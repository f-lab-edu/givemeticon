#!/usr/bin/env python3
"""compare-workload.py 대체 실행 테스트(합성 원본 로그)."""
import json, pathlib, subprocess, sys, tempfile, unittest
HERE = pathlib.Path(__file__).parent

def v1_logs(n, start, every, targets=2, drop_dup=None, extra_dup=None):
    out = []
    for i in range(n):
        out.append(json.dumps({"kind": "admission", "memberId": start + i, "target": i % targets + 1, "category": "success"}))
        if i % every == 0 and i != drop_dup: out.append(json.dumps({"kind": "duplicate", "memberId": start + i, "target": (i + 1) % targets + 1, "category": "success"}))
    if extra_dup is not None: out.append(json.dumps({"kind": "duplicate", "memberId": start + extra_dup, "target": 1}))
    return out

def stock_logs(n, start, every, targets=2, same_app_dup=False):
    out = []
    for i in range(n):
        out.append(json.dumps({"kind": "stock_request", "attempt": 1, "userId": start + i, "target": i % targets + 1, "category": "success"}))
        if i % every == 0:
            t = (i % targets + 1) if same_app_dup else ((i + 1) % targets + 1)
            out.append(json.dumps({"kind": "stock_request", "attempt": 2, "userId": start + i, "target": t, "category": "business_rejected"}))
    return out

def cmp(v1, stock, v1s=700200000, ss=900000000):
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t); (t / "v1").mkdir(); (t / "st").mkdir()
        (t / "v1/k6-failures.log").write_text("\n".join(v1)); (t / "st/k6-failures.log").write_text("\n".join(stock))
        p = subprocess.run([sys.executable, "-B", str(HERE / "compare-workload.py"), str(t / "v1"), str(v1s), str(t / "st"), str(ss)], capture_output=True, text=True)
        return p.returncode, json.loads(p.stdout)

class T(unittest.TestCase):
    def test_same_contract_10k_10pct(self):
        rc, r = cmp(v1_logs(10000, 700200000, 10), stock_logs(10000, 900000000, 10))
        self.assertEqual(rc, 0); self.assertEqual(r["verdict"], "SAME_WORKLOAD_CONTRACT"); self.assertEqual(r["stock"]["duplicates"], 1000)
        self.assertEqual(r["v1"]["app_split(target1,target2)"], [5000, 5000])
    def test_unique_only_stock_is_different_from_v1_main_contract(self):
        rc, r = cmp(v1_logs(1000, 700200000, 10), [l for l in stock_logs(1000, 900000000, 10) if '"attempt": 1' in l])
        self.assertEqual(rc, 1); self.assertFalse(r["identical"]["dup_offsets"]); self.assertEqual(r["stock"]["duplicates"], 0)
    def test_different_dup_selection_detected(self):
        rc, r = cmp(v1_logs(1000, 700200000, 10, drop_dup=20), stock_logs(1000, 900000000, 10))
        self.assertEqual(rc, 1); self.assertEqual(r["verdict"], "DIFFERENT")
    def test_same_app_duplicate_detected(self):
        rc, r = cmp(v1_logs(100, 700200000, 10), stock_logs(100, 900000000, 10, same_app_dup=True))
        self.assertEqual(rc, 1); self.assertFalse(r["stock"]["dup_target_is_other_app"])
    def test_different_size_detected(self):
        rc, r = cmp(v1_logs(1000, 700200000, 10), stock_logs(999, 900000000, 10))
        self.assertEqual(rc, 1); self.assertFalse(r["identical"]["first_count"])
if __name__ == "__main__": unittest.main()
