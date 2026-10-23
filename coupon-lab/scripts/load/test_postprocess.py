"""Checks postprocess.py against synthetic k6 output. Run: python3 -I -m unittest discover -s scripts/load -v"""
import gzip
import importlib.util
import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("postprocess", os.path.join(HERE, "postprocess.py"))
postprocess = importlib.util.module_from_spec(spec)
spec.loader.exec_module(postprocess)

with open(os.path.join(HERE, "profiles.json"), encoding="utf-8") as f:
    LAYOUT = json.load(f)

T0 = datetime(2026, 10, 23, 12, 0, 0, tzinfo=timezone(timedelta(hours=9)))  # k6 prints local offsets


def stamp(offset_s):
    return (T0 + timedelta(seconds=offset_s)).isoformat(timespec="microseconds")


def point(metric, offset_s, value, **tags):
    return json.dumps({"type": "Point", "data": {"time": stamp(offset_s), "value": value, "tags": tags}, "metric": metric})


def window_of(profile, index):
    """(measure start, measure end) in seconds from the first request, from the same layout the script uses."""
    cursor = LAYOUT["warmup"]["seconds"] + sum(s["seconds"] for s in LAYOUT["profiles"][profile]["steps"][:index])
    step = LAYOUT["profiles"][profile]["steps"][index]
    return cursor + LAYOUT["transition_seconds"], cursor + step["seconds"]


def issued_count(lines):
    return sum(1 for line in lines if json.loads(line)["data"].get("tags", {}).get("result") == "issued")


def request_lines(offset_s, ms, phase, rps, result):
    tags = dict(phase=phase, target_rps=str(rps))
    return [point("http_req_duration", offset_s + ms / 1000.0, ms, status="200", **tags),
            point("coupon_results", offset_s + ms / 1000.0, 1, result=result, **tags)]


class PostprocessTest(unittest.TestCase):
    def report(self, lines, profile="ramping", db_rows=None, filtered=None):
        with tempfile.TemporaryDirectory() as tmp:
            raw = os.path.join(tmp, "raw.json.gz")
            with gzip.open(raw, "wt", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            if db_rows is None:
                db_rows = issued_count(lines)
            out = postprocess.build_report(raw, LAYOUT, profile, "s0", db_rows, {}, filtered)
            if filtered:
                with gzip.open(filtered, "rt", encoding="utf-8") as f:
                    out["_filtered_lines"] = f.read().splitlines()
            return out

    def synthetic(self, slow_from=None, drop_in=None):
        """First request at t=0. Every step gets 100 responses of 1..100 ms; steps from slow_from get +900 ms."""
        lines = [point("http_req_duration", 0.01, 10, status="200", phase="warmup", target_rps="100")]
        lines.append(point("coupon_results", 0.01, 1, result="issued", phase="warmup", target_rps="100"))
        steps = LAYOUT["profiles"]["ramping"]["steps"]
        for index, step in enumerate(steps):
            start, end = window_of("ramping", index)
            extra = 900 if slow_from is not None and index >= slow_from else 0
            for i in range(100):
                at = start + (end - start) * i / 100.0
                lines += request_lines(at, i + 1 + extra, "measure", step["rps"], "issued")
            # a slow transition-phase request must not leak into the measure statistics
            lines += request_lines(start - 0.5, 5000, "transition", step["rps"], "issued")
            if drop_in == index:
                lines.append(point("dropped_iterations", start + 1, 50))
        return lines

    def test_parse_time_handles_nanoseconds_and_offsets(self):
        a = postprocess.parse_time("2026-10-23T12:00:00.123456789+09:00")
        b = postprocess.parse_time("2026-10-23T03:00:00.123456789Z")
        self.assertAlmostEqual(a, b, places=6)
        self.assertAlmostEqual(postprocess.parse_time("2026-10-23T03:00:01Z") - b, 0.876543, places=5)

    def test_measure_phase_only_and_percentiles(self):
        report = self.report(self.synthetic())
        self.assertEqual([s["target_rps"] for s in report["stages"]], [100, 500, 1000, 2000, 3000])
        stage = report["stages"][0]
        self.assertEqual(stage["requests"], 100)
        self.assertEqual(stage["issued"], 100)
        self.assertEqual(stage["errors"], 0)
        self.assertEqual(stage["p50_ms"], 50.5)
        self.assertEqual(stage["p95_ms"], 95.05)
        self.assertEqual(stage["p99_ms"], 99.01)
        self.assertEqual(stage["achieved_rps"], round(100 / 29.0, 1))
        self.assertEqual(report["limit_rps"], "not reached")
        self.assertTrue(report["validity"]["valid"])
        self.assertEqual(report["db"]["match"], True)
        self.assertEqual(report["db"]["http_issued"], 1 + 5 * 101)  # warmup + measure + transition

    def test_limit_is_the_step_before_the_first_violation(self):
        report = self.report(self.synthetic(slow_from=3))
        self.assertGreater(report["stages"][3]["p95_ms"], 500)
        self.assertEqual(report["limit_rps"], 1000)

    def test_violation_at_the_first_step_has_no_previous_step(self):
        report = self.report(self.synthetic(slow_from=0))
        self.assertEqual(report["limit_rps"], "below_min_stage")

    def test_error_rate_above_one_percent_is_a_violation(self):
        lines = self.synthetic()
        start, _ = window_of("ramping", 1)
        lines += request_lines(start + 1, 10, "measure", 500, "error") * 2  # 2 errors in 102 requests
        report = self.report(lines)
        self.assertAlmostEqual(report["stages"][1]["error_rate"], 2 / 102, places=5)
        self.assertEqual(report["limit_rps"], 100)

    def test_db_mismatch_makes_the_run_invalid(self):
        lines = self.synthetic()
        report = self.report(lines, db_rows=issued_count(lines) - 1)
        self.assertFalse(report["db"]["match"])
        self.assertFalse(report["validity"]["valid"])

    def test_dropped_iterations_are_assigned_by_time(self):
        report = self.report(self.synthetic(drop_in=2))
        self.assertEqual([s["dropped_iterations"] for s in report["stages"]], [0, 0, 50, 0, 0])
        self.assertAlmostEqual(report["validity"]["dropped_ratio"], 50 / (500 + 50), places=5)
        self.assertFalse(report["validity"]["valid"])

    def test_filtered_raw_reproduces_the_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            filtered = os.path.join(tmp, "filtered.json.gz")
            noisy = self.synthetic() + [point("http_req_blocked", 1, 0.1), '{"type":"Metric","data":{},"metric":"http_reqs"}']
            first = self.report(noisy, filtered=filtered)
            self.assertEqual(len(first["_filtered_lines"]), len(self.synthetic()))
            second = postprocess.build_report(filtered, LAYOUT, "ramping", "s0", first["db"]["issued_rows"], {})
            del first["_filtered_lines"]
            self.assertEqual(first, second)

    def test_spike_profile_has_a_single_step(self):
        start, end = window_of("spike", 0)
        lines = [point("http_req_duration", 0.01, 10, status="200", phase="warmup", target_rps="100")]
        for i in range(10):
            lines += request_lines(start + i, 20, "measure", 3000, "sold_out")
        lines += request_lines(start + 11, 20, "measure", 3000, "issued")
        report = self.report(lines, profile="spike", db_rows=1)
        self.assertEqual(report["stock"], 100)
        self.assertEqual(report["stages"][0]["sold_out"], 10)
        self.assertEqual(report["stages"][0]["achieved_rps"], round(11 / (end - start), 1))
        self.assertTrue(report["validity"]["valid"])


if __name__ == "__main__":
    unittest.main()
