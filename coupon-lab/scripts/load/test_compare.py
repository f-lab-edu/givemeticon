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


def report(stage, kinds, p95, achieved, sustained, env=None, order=None, stalls=0, p50=(2.0, 3.0, 4.0), locks=None,
           profile="ramping", rows=None, targets=TARGETS):
    stages = [{"target_rps": t, "kind": k, "p95_ms": p, "achieved_rps": a, "p50_ms": m}
              for t, k, p, a, m in zip(targets, kinds, p95, achieved, p50)]
    for stage_row, values in zip(stages, locks or []):
        stage_row.update(values)
    rep = {"profile": profile, "stage": stage, "stages": stages, "sustained_limit_rps": sustained,
           "max_achieved_rps": {"achieved_rps": max(achieved), "target_rps": targets[achieved.index(max(achieved))]},
           "stalls": [{"target_rps": 200}] * stalls, "limit_rps_first_violation": 100,
           "validity": {"valid": True}, "run": {"run_order": order} if order else {},
           "env": env or {"k6_mode": "host", "cpu": 8}}
    if rows is not None:
        rep["rows"] = rows
    return rep


def ok3(stage, sustained, **kw):
    return report(stage, ["ok", "ok", "ok"], [5, 9, 20], [100, 200, 300], sustained, **kw)


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
        self.assertIn("# Load test comparison (5 runs, 2 groups)", text)
        # both groups have the steps 100/200/300, so the common target is 300 and the groups table compares p50 there
        self.assertIn("p50 and row-lock at the highest target all groups share: 300 rps", text)
        self.assertIn("| s0 ramping rows=1 flush=1 pool=- | 3 | 300 (200-300) | 300 (250-300) | 4 (4-4) | - | 1 |", text)
        self.assertIn("| s1 ramping rows=1 flush=1 pool=- | 2 | 150 (100-200) | 250 (240-260) | 700 (500-900) | - | 0 |", text)  # even count
        self.assertIn("| s0 ramping rows=1 flush=1 pool=- | s0-c | container | 5 | 300 | 300 (at 300) | - | 1 | 100 | yes |", text)
        # target 200: s0 p95 median of 10, 12, 700 = 12; achieved median 200; kinds ok/stall/saturated = 2/1/0
        self.assertIn("| 200 | 12 | 200 | 2/1/0 |", text)
        self.assertIn("Note: the runs of s0 ramping rows=1 flush=1 pool=- are pooled although their env differs - k6_mode: container, host.", text)
        self.assertNotIn("Note: the runs of s1", text)

    def test_runs_are_grouped_by_stage_profile_rows_flush_and_pool(self):
        with tempfile.TemporaryDirectory() as root:
            variants = [dict(), dict(rows=4), dict(rows=16), dict(env={"innodb_flush_log_at_trx_commit": 2}),
                        dict(env={"hikari_pool_size": 5}), dict(env={"hikari_pool_size": 20}), dict(env={"hikari_pool_size": 5})]
            for index, extra in enumerate(variants):
                self.write(root, "r%d" % index, ok3("s1", 300, **extra))
            self.write(root, "other-stage", ok3("s0", 300))
            self.write(root, "other-profile", ok3("s1", 300, profile="ramping-rows"))
            text = self.run_compare([os.path.join(root, "*")])
        self.assertIn("(9 runs, 8 groups)", text)
        self.assertIn("| s1 ramping rows=4 flush=1 pool=- | 1 |", text)
        self.assertIn("| s1 ramping rows=1 flush=2 pool=- | 1 |", text)
        self.assertIn("| s1 ramping rows=1 flush=1 pool=5 | 2 |", text)  # two runs share a group
        self.assertIn("| s1 ramping rows=1 flush=1 pool=20 | 1 |", text)
        self.assertIn("| s1 ramping-rows rows=1 flush=1 pool=- | 1 |", text)

    def test_a_run_from_before_flush_log_was_recorded_counts_as_flush_1(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "old", ok3("s0", 300))
            self.write(root, "new", ok3("s0", 300, env={"k6_mode": "host", "innodb_flush_log_at_trx_commit": 1}))
            text = self.run_compare([os.path.join(root, "*")])
        self.assertIn("(2 runs, 1 groups)", text)
        self.assertIn("innodb_flush_log_at_trx_commit: (missing), 1", text)

    def test_groups_with_different_profiles_are_compared_at_the_highest_common_target(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "a1", ok3("s1", 300, p50=(1.0, 2.0, 30.0),
                                       locks=[{"row_lock_wait_ratio": r} for r in (0.1, 0.2, 0.95)]))
            self.write(root, "a2", ok3("s1", 300, p50=(1.0, 2.0, 50.0),
                                       locks=[{"row_lock_wait_ratio": r} for r in (0.1, 0.2, 0.85)]))
            wide = report("s1", ["ok"] * 3, [5, 9, 20], [200, 300, 400], 400, profile="ramping-rows", rows=4,
                          targets=(200, 300, 400), p50=(1.0, 2.0, 3.0),
                          locks=[{"row_lock_wait_ratio": r} for r in (0.01, 0.02, 0.03)])
            self.write(root, "b1", wide)
            text = self.run_compare([os.path.join(root, "*")])
        self.assertIn("all groups share: 300 rps", text)  # 100/200/300 and 200/300/400 overlap in 200 and 300
        self.assertIn("| s1 ramping rows=1 flush=1 pool=- | 2 | 300 (300-300) | 300 (300-300) | 40 (30-50) | 0.900 (0.850-0.950) | 0 |", text)
        self.assertIn("| s1 ramping-rows rows=4 flush=1 pool=- | 1 | 400 (400-400) | 400 (400-400) | 2 (2-2) | 0.020 (0.020-0.020) | 0 |", text)
        # one per-target table per layout
        self.assertIn("steps 100/200/300", text)
        self.assertIn("steps 200/300/400", text)

    def test_without_a_common_target_the_groups_table_says_so(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "a", ok3("s0", 300))
            self.write(root, "b", report("s0", ["ok"] * 3, [5, 9, 20], [1000, 2000, 3000], 3000, profile="ramping-rows",
                                         targets=(1000, 2000, 3000)))
            text = self.run_compare([os.path.join(root, "*")])
        self.assertIn("all groups share: - rps", text)
        self.assertIn("| s0 ramping rows=1 flush=1 pool=- | 1 | 300 (300-300) | 300 (300-300) | - | - | 0 |", text)

    def test_the_row_lock_table_shows_the_reference_and_each_runs_highest_target(self):
        locks = [{}, {"row_lock_wait_ratio": 0.12, "row_lock_time_per_request_ms": 0.5, "fsyncs_per_request": 1.0},
                 {"row_lock_wait_ratio": 0.98, "row_lock_time_per_request_ms": 41.25, "fsyncs_per_request": 0.5}]
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "x", report("s1", ["ok", "ok", "saturated"], [5, 9, 700], [100, 200, 250], 200, locks=locks))
            self.write(root, "y", ok3("s1", 300))  # no counters: dashes
            text = self.run_compare([os.path.join(root, "x"), os.path.join(root, "y"), "--p50-at", "200"])
        self.assertIn("| group | run | ref rps | wait_ratio | lock ms/req | fsyncs/req | top rps |", text)
        self.assertIn("| s1 ramping rows=1 flush=1 pool=- | x | 200 | 0.120 | 0.500 | 1 | 300 | 0.980 | 41.250 | 0.500 |", text)
        self.assertIn("| s1 ramping rows=1 flush=1 pool=- | y | 200 | - | - | - | 300 | - | - | - |", text)

    def test_the_p50_column_follows_the_requested_target(self):
        with tempfile.TemporaryDirectory() as root:
            self.make(root)
            text = self.run_compare([os.path.join(root, "s1-*"), "--p50-at", "300"])
        self.assertIn("p50@300 ms", text)
        self.assertIn("| s1 ramping rows=1 flush=1 pool=- | s1-a | host | 2 | 200 | 260 (at 300) | 500 | 0 | 100 | yes |", text)

    def test_runs_can_be_given_as_directories_or_files(self):
        with tempfile.TemporaryDirectory() as root:
            self.make(root)
            text = self.run_compare([os.path.join(root, "s1-a"), os.path.join(root, "s1-b", "load.json")])
        self.assertIn("(2 runs, 1 groups)", text)
        self.assertNotIn("| s0 ramping", text)

    def test_a_run_without_the_new_fields_is_refused(self):
        with tempfile.TemporaryDirectory() as root:
            old = ok3("s0", 300)
            del old["sustained_limit_rps"]
            directory = self.write(root, "old", old)
            with self.assertRaises(SystemExit) as caught:
                compare.main([directory])
        self.assertIn("reprocess", str(caught.exception))

    def test_runs_of_one_group_with_different_layouts_are_refused(self):
        with tempfile.TemporaryDirectory() as root:
            a = self.write(root, "a", ok3("s0", 300))
            other = ok3("s0", 300)
            other["stages"][2]["target_rps"] = 999
            b = self.write(root, "b", other)
            with self.assertRaises(SystemExit) as caught:
                compare.main([a, b])
        self.assertIn("layout", str(caught.exception))

    def test_no_ok_stage_is_shown_as_a_dash(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "x", report("s0", ["saturated"] * 3, [900, 900, 900], [50, 60, 70], None))
            text = self.run_compare([os.path.join(root, "x")])
        self.assertIn("| s0 ramping rows=1 flush=1 pool=- | 1 | - |", text)


if __name__ == "__main__":
    unittest.main()
