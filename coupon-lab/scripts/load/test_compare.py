"""Checks compare.py against hand-made load.json files. Run: python3 -I -m unittest discover -s scripts/load -v"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("compare", os.path.join(HERE, "compare.py"))
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)

TARGETS = (100, 200, 300)


def report(stage, kinds, p95, achieved, sustained, env=None, order=None, stalls=0, p50=(2.0, 3.0, 4.0), locks=None):
    stages = [{"target_rps": t, "kind": k, "p95_ms": p, "achieved_rps": a, "p50_ms": m}
              for t, k, p, a, m in zip(TARGETS, kinds, p95, achieved, p50)]
    for stage_row, values in zip(stages, locks or []):
        stage_row.update(values)
    return {"profile": "ramping", "stage": stage, "stages": stages, "sustained_limit_rps": sustained,
            "max_achieved_rps": {"achieved_rps": max(achieved), "target_rps": TARGETS[achieved.index(max(achieved))]},
            "stalls": [{"target_rps": 200}] * stalls, "limit_rps_first_violation": 100,
            "validity": {"valid": True}, "run": {"run_order": order} if order else {},
            "env": env or {"k6_mode": "host", "cpu": 8}}


class CompareTest(unittest.TestCase):
    def write(self, root, name, rep):
        directory = os.path.join(root, name)
        os.makedirs(directory)
        with open(os.path.join(directory, "load.json"), "w", encoding="utf-8") as f:
            json.dump(rep, f)
        return directory

    def run_compare(self, argv):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            compare.main(argv)
        return buffer.getvalue()

    def make(self, root):
        self.write(root, "s0-a", report("s0", ["ok", "ok", "saturated"], [5, 10, 900], [100, 200, 250], 200, order=1))
        self.write(root, "s0-b", report("s0", ["ok", "ok", "ok"], [7, 12, 40], [100, 200, 300], 300, order=3))
        self.write(root, "s0-c", report("s0", ["ok", "stall", "ok"], [9, 700, 50], [100, 200, 300], 300,
                                        env={"k6_mode": "container", "cpu": 8}, order=5, stalls=1))
        self.write(root, "s1-a", report("s1", ["ok", "ok", "saturated"], [6, 11, 800], [100, 200, 260], 200, order=2,
                                        p50=(2.0, 30.0, 500.0)))
        self.write(root, "s1-b", report("s1", ["ok", "saturated", "saturated"], [8, 600, 900], [100, 190, 240], 100, order=4,
                                        p50=(2.0, 700.0, 900.0)))

    def test_tables_contain_medians_ranges_and_counts(self):
        with tempfile.TemporaryDirectory() as root:
            self.make(root)
            out = os.path.join(root, "out", "compare.md")
            text = self.run_compare([os.path.join(root, "s*"), "--out", out])
            with open(out, encoding="utf-8") as f:
                self.assertEqual(text, f.read())
        self.assertIn("| s0 | 3 | 300 (200-300) | 300 (250-300) | - | 1 |", text)  # default 650 is not in this layout
        self.assertIn("| s1 | 2 | 150 (100-200) | 250 (240-260) | - | 0 |", text)  # median of an even count
        self.assertIn("| s0 | s0-c | container | 5 | 300 | 300 (at 300) | - | 1 | 100 | yes |", text)
        # target 200: s0 p95 median of 10, 12, 700 = 12; achieved median 200; kinds ok/stall/saturated = 2/1/0
        self.assertIn("| 200 | 12 | 200 | 2/1/0 |", text)
        self.assertIn("Note: the s0 runs are pooled although their env differs - k6_mode: container, host.", text)
        self.assertNotIn("Note: the s1 runs", text)

    def test_the_p50_column_follows_the_requested_target(self):
        with tempfile.TemporaryDirectory() as root:
            self.make(root)
            text = self.run_compare([os.path.join(root, "s1-*"), "--p50-at", "300"])
        self.assertIn("p50@300 ms", text)
        self.assertIn("| s1 | s1-a | host | 2 | 200 | 260 (at 300) | 500 | 0 | 100 | yes |", text)
        self.assertIn("| s1 | 2 | 150 (100-200) | 250 (240-260) | 700 (500-900) | 0 |", text)
        with tempfile.TemporaryDirectory() as root2:
            self.make(root2)
            low = self.run_compare([os.path.join(root2, "s0-a"), "--p50-at", "100"])
        self.assertIn("p50@100 ms", low)
        self.assertIn("| s0 | s0-a | host | 1 | 200 | 250 (at 300) | 2 | 0 | 100 | yes |", low)  # p50 2.0 at 100 rps

    def test_the_row_lock_table_shows_the_reference_and_the_highest_target(self):
        locks = [{}, {"row_lock_wait_ratio": 0.12, "row_lock_time_per_request_ms": 0.5, "fsyncs_per_request": 1.0},
                 {"row_lock_wait_ratio": 0.98, "row_lock_time_per_request_ms": 41.25, "fsyncs_per_request": 0.5}]
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "x", report("s1", ["ok", "ok", "saturated"], [5, 9, 700], [100, 200, 250], 200, locks=locks))
            self.write(root, "y", report("s1", ["ok", "ok", "ok"], [5, 9, 20], [100, 200, 300], 300))  # no counters: dashes
            text = self.run_compare([os.path.join(root, "x"), os.path.join(root, "y"), "--p50-at", "200"])
        self.assertIn("wait_ratio@200 | lock ms/req@200 | fsyncs/req@200 | wait_ratio@300 | lock ms/req@300 | fsyncs/req@300", text)
        self.assertIn("| s1 | x | 0.120 | 0.500 | 1 | 0.980 | 41.250 | 0.500 |", text)
        self.assertIn("| s1 | y | - | - | - | - | - | - |", text)

    def test_runs_can_be_given_as_directories_or_files(self):
        with tempfile.TemporaryDirectory() as root:
            self.make(root)
            text = self.run_compare([os.path.join(root, "s1-a"), os.path.join(root, "s1-b", "load.json")])
        self.assertIn("| s1 | 2 |", text)
        self.assertNotIn("| s0 |", text)

    def test_a_run_without_the_new_fields_is_refused(self):
        with tempfile.TemporaryDirectory() as root:
            old = report("s0", ["ok"] * 3, [1, 1, 1], [100, 200, 300], 300)
            del old["sustained_limit_rps"]
            directory = self.write(root, "old", old)
            with self.assertRaises(SystemExit) as caught:
                compare.main([directory])
        self.assertIn("reprocess", str(caught.exception))

    def test_runs_with_different_layouts_are_refused(self):
        with tempfile.TemporaryDirectory() as root:
            a = self.write(root, "a", report("s0", ["ok"] * 3, [1, 1, 1], [100, 200, 300], 300))
            other = report("s1", ["ok"] * 3, [1, 1, 1], [100, 200, 300], 300)
            other["stages"][2]["target_rps"] = 999
            b = self.write(root, "b", other)
            with self.assertRaises(SystemExit) as caught:
                compare.main([a, b])
        self.assertIn("layout", str(caught.exception))

    def test_no_ok_stage_is_shown_as_a_dash(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "x", report("s0", ["saturated"] * 3, [900, 900, 900], [50, 60, 70], None))
            text = self.run_compare([os.path.join(root, "x")])
        self.assertIn("| s0 | 1 | - |", text)


if __name__ == "__main__":
    unittest.main()
