#!/usr/bin/env python3
"""analyze-bottleneck.py 단위 테스트. 픽스처는 임시 디렉터리에 직접 만들며 실제 결과 파일은 쓰지 않는다."""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("analyze_bottleneck", ROOT / "analyze-bottleneck.py")
ab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ab)

TX = "coupon_admission_transaction_seconds"


def hist_lines(base, buckets, count, total, **labels):
    """buckets: [(le, 누적 count)]. 라벨은 application과 함께 붙는다."""
    lab = "".join(f',{k}="{v}"' for k, v in labels.items())
    lines = [f'{base}_bucket{{application="x"{lab},le="{le}"}} {c}' for le, c in buckets]
    lines.append(f'{base}_count{{application="x"{lab}}} {count}')
    lines.append(f'{base}_sum{{application="x"{lab}}} {total}')
    return "\n".join(lines) + "\n"


class AnalyzeBottleneckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def warnings(self):
        return {"negative_delta": [], "non_monotonic_buckets": [], "upper_bound_hit": [], "missing_file": [], "parse_errors": []}

    def make_run(self, name, per_app, env_extra="", extra_files=None):
        """per_app: {app: (before 텍스트, quiescent 텍스트)}"""
        run = self.dir / name
        run.mkdir()
        (run / "run.env").write_text(f"run_id={name}\nkind=measurement\nrate=1000\nduration=10s\n"
                                     f"monitor_lock_introspection=false\n{env_extra}")
        for app, (before, quiescent) in per_app.items():
            (run / f"{app}-before.prom").write_text(before)
            (run / f"{app}-quiescent.prom").write_text(quiescent)
            (run / f"{app}-after.prom").write_text(quiescent)
        for fname, content in (extra_files or {}).items():
            (run / fname).write_text(content)
        return run

    def test_quantile_interpolation(self):
        h = ab.Hist()
        h.buckets = {0.01: 10, 0.05: 60, 0.1: 90, float("inf"): 100}
        p50, flag50 = ab.quantile(h, 0.50)
        self.assertAlmostEqual(p50, 0.01 + (50 - 10) / (60 - 10) * 0.04)
        self.assertAlmostEqual(p50, 0.042)
        self.assertIsNone(flag50)
        p95, flag95 = ab.quantile(h, 0.95)
        self.assertEqual(p95, 0.1)
        self.assertEqual(flag95, "upper_bound_hit")

    def test_delta_uses_before_snapshot(self):
        before = f'{TX}_count{{application="x",outcome="committed"}} 100\n'
        quiescent = f'{TX}_count{{application="x",outcome="committed"}} 130\n'
        w = self.warnings()
        d = ab.delta_snapshot(ab.parse_prom_text(before)[0], ab.parse_prom_text(quiescent)[0], "app1", w)
        groups = ab.collect(d, TX, {}, ("outcome",))
        self.assertEqual(groups[("committed",)].count, 30)
        self.assertEqual(w["negative_delta"], [])

    def test_negative_delta_is_reported_not_crashing(self):
        before = (f'{TX}_count{{application="x",outcome="committed"}} 100\n'
                  'coupon_admission_commit_seconds_count{application="x"} 10\n')
        quiescent = (f'{TX}_count{{application="x",outcome="committed"}} 40\n'
                     'coupon_admission_commit_seconds_count{application="x"} 25\n')
        run = self.make_run("neg", {"app1": (before, quiescent)})
        result = ab.analyze_run(str(run))
        self.assertEqual([x["series"] for x in result["warnings"]["negative_delta"]], [f"{TX}_count"])
        self.assertEqual(result["warnings"]["negative_delta"][0]["app"], "app1")
        self.assertEqual(result["metrics"]["commit"]["combined"]["all"]["count"], 15)  # 다른 시리즈는 정상 계산
        self.assertEqual(result["metrics"]["admission_tx"]["combined"]["all"]["count"], 0)  # 문제 시리즈는 제외

    def test_combined_quantile_merges_buckets(self):
        # app1: 모두 빠름(p50≈0.0055), app2: 모두 느림(p50≈0.055). 앱별 p50의 평균(≈0.030)과 합산 p50은 달라야 한다.
        a1 = hist_lines(TX, [("0.01", 100), ("0.1", 100), ("+Inf", 100)], 100, 0.5, outcome="committed")
        a2 = hist_lines(TX, [("0.01", 0), ("0.1", 100), ("+Inf", 100)], 100, 5.5, outcome="committed")
        run = self.make_run("merge", {"app1": ("", a1), "app2": ("", a2)})
        result = ab.analyze_run(str(run))
        entry = result["metrics"]["admission_tx"]
        p1, p2 = entry["per_app"]["app1"]["all"]["p50"], entry["per_app"]["app2"]["all"]["p50"]
        combined = entry["combined"]["all"]["p50"]
        self.assertAlmostEqual(combined, 10.0)  # 합산 bucket: 0.01:100 / 0.1:200 / +Inf:200 -> rank 100 -> le 0.01 경계 = 10ms
        self.assertNotAlmostEqual(combined, (p1 + p2) / 2)
        self.assertEqual(entry["combined"]["all"]["count"], 200)

    def test_caller_tags_are_separated(self):
        base = "coupon_admission_select_for_update_seconds"
        q = (hist_lines(base, [("0.01", 4), ("+Inf", 4)], 4, 0.02, caller="admission", outcome="success")
             + hist_lines(base, [("0.01", 1), ("+Inf", 7)], 7, 0.5, caller="issuance", outcome="success"))
        run = self.make_run("caller", {"app1": ("", q)})
        lock = ab.analyze_run(str(run))["metrics"]["lock_select"]["combined"]
        self.assertEqual(lock["caller=admission"]["count"], 4)
        self.assertEqual(lock["caller=issuance"]["count"], 7)
        self.assertEqual(set(lock["groups"]), {"admission|success", "issuance|success"})

    def test_label_escape_parsing(self):
        text = 'm_count{application="x",uri="/a\\"b\\\\c",} 5\n# HELP m_count x\nm_sum 2.5\n'
        parsed, bad = ab.parse_prom_text(text)
        self.assertEqual(bad, 0)
        self.assertEqual(parsed[("m_count", (("application", "x"), ("uri", '/a"b\\c')))], 5.0)
        self.assertEqual(parsed[("m_sum", ())], 2.5)

    def test_lock_csv_delta_and_avg_wait(self):
        csv_text = ("host_before_ms,host_after_ms,server_ms,row_lock_waits,row_lock_time_ms,row_lock_current_waits\r\n"
                    "1,2,3,3,10,0\r\n"
                    "2,3,4,8,60,2\r\n"
                    "3,4,5,13,110,1\r\n")
        run = self.make_run("lock", {"app1": ("", "")}, extra_files={"fast-mysql-locks.csv": csv_text})
        lk = ab.analyze_run(str(run))["timeseries"]["mysql_locks"]
        self.assertEqual(lk["delta_row_lock_waits"], 10)
        self.assertEqual(lk["delta_row_lock_time_ms"], 100)
        self.assertEqual(lk["avg_wait_ms"], 10)
        self.assertEqual(lk["row_lock_current_waits_max"], 2)

    def test_no_judgement_words_in_markdown(self):
        q = hist_lines(TX, [("0.01", 5), ("+Inf", 5)], 5, 0.02, outcome="committed")
        run = self.make_run("md", {"app1": ("", q), "app2": ("", q)})
        out = self.dir / "out"
        proc = subprocess.run([sys.executable, "-B", str(ROOT / "analyze-bottleneck.py"), str(out), str(run)],
                              capture_output=True, text=True, stdin=subprocess.DEVNULL)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        md = (out / "bottleneck-summary.md").read_text()
        for word in ("결론", "병목은", "원인은"):
            self.assertNotIn(word, md)
        self.assertIn(ab.FIXED_NOTE, md)
        summary = json.loads((out / "bottleneck-summary.json").read_text())
        self.assertEqual(summary["runs"][0]["env"]["monitor_lock_introspection"], "false")


if __name__ == "__main__":
    unittest.main()
