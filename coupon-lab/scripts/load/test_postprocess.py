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

# A fixed layout, independent of profiles.json, so that retuning the real profiles cannot break these checks.
LAYOUT = {
    "warmup": {"rps": 100, "seconds": 15},
    "transition_seconds": 1,
    "profiles": {
        "ramping": {"stock": 10000000, "steps": [{"rps": r, "seconds": 30} for r in (100, 500, 1000, 2000, 3000)]},
        "spike": {"stock": 100, "steps": [{"rps": 3000, "seconds": 21}]},
    },
}

T0 = datetime(2026, 10, 23, 12, 0, 0, tzinfo=timezone(timedelta(hours=9)))  # k6 prints local offsets


def stamp(offset_s):
    return (T0 + timedelta(seconds=offset_s)).isoformat(timespec="microseconds")


def point(metric, offset_s, value, **tags):
    return json.dumps({"type": "Point", "data": {"time": stamp(offset_s), "value": value, "tags": tags}, "metric": metric})


def window_of(profile, index):
    """(measure start, measure end) in seconds from the first request, from the same layout the script uses."""
    steps = LAYOUT["profiles"][profile]["steps"]
    cursor = LAYOUT["warmup"]["seconds"] + sum(s["seconds"] for s in steps[:index])
    return cursor + LAYOUT["transition_seconds"], cursor + steps[index]["seconds"]


def issued_count(lines):
    return sum(1 for line in lines if json.loads(line)["data"].get("tags", {}).get("result") == "issued")


def request_lines(offset_s, ms, phase, rps, result):
    tags = dict(phase=phase, target_rps=str(rps))
    return [point("http_req_duration", offset_s + ms / 1000.0, ms, status="200", **tags),
            point("coupon_results", offset_s + ms / 1000.0, 1, result=result, **tags)]


def synthetic(slow_steps=(), drops=None):
    """First request at t=0. Every step gets 100 responses of 1..100 ms; steps in slow_steps get +900 ms.
    drops maps a step index to the number of dropped iterations in that step."""
    drops = drops or {}
    lines = [point("http_req_duration", 0.01, 10, status="200", phase="warmup", target_rps="100"),
             point("coupon_results", 0.01, 1, result="issued", phase="warmup", target_rps="100")]
    for index, step in enumerate(LAYOUT["profiles"]["ramping"]["steps"]):
        start, end = window_of("ramping", index)
        extra = 900 if index in slow_steps else 0
        for i in range(100):
            lines += request_lines(start + (end - start) * i / 100.0, i + 1 + extra, "measure", step["rps"], "issued")
        # a slow transition-phase request must not leak into the measure statistics
        lines += request_lines(start - 0.5, 5000, "transition", step["rps"], "issued")
        if index in drops:
            lines.append(point("dropped_iterations", start + 1, drops[index]))
    return lines


def write_raw(path, lines):
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


class PostprocessTest(unittest.TestCase):
    def report(self, lines, profile="ramping", db_rows=None, filtered=None):
        with tempfile.TemporaryDirectory() as tmp:
            raw = os.path.join(tmp, "raw.json.gz")
            write_raw(raw, lines)
            if db_rows is None:
                db_rows = issued_count(lines)
            out = postprocess.build_report(raw, LAYOUT, profile, "s0", db_rows, {}, filtered)
            if filtered:
                with gzip.open(filtered, "rt", encoding="utf-8") as f:
                    out["_filtered_lines"] = f.read().splitlines()
            return out

    def test_parse_time_handles_nanoseconds_and_offsets(self):
        a = postprocess.parse_time("2026-10-23T12:00:00.123456789+09:00")
        b = postprocess.parse_time("2026-10-23T03:00:00.123456789Z")
        self.assertAlmostEqual(a, b, places=6)
        self.assertAlmostEqual(postprocess.parse_time("2026-10-23T03:00:01Z") - b, 0.876543, places=5)

    def test_measure_phase_only_and_percentiles(self):
        report = self.report(synthetic())
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
        self.assertTrue(all(s["valid"] and not s["saturated"] for s in report["stages"]))
        self.assertEqual(report["db"]["http_issued"], 1 + 5 * 101)  # warmup + measure + transition

    def test_slow_step_is_the_limit_and_everything_after_it_is_saturated(self):
        report = self.report(synthetic(slow_steps={3, 4}))
        self.assertEqual(report["limit_rps"], 1000)
        self.assertEqual([s["saturated"] for s in report["stages"]], [False, False, False, True, True])
        self.assertTrue(report["validity"]["valid"])  # saturating later stages does not invalidate the run

    def test_a_good_stage_after_the_first_bad_one_is_still_saturated(self):
        report = self.report(synthetic(slow_steps={2}))
        self.assertEqual(report["limit_rps"], 500)
        self.assertEqual([s["saturated"] for s in report["stages"]], [False, False, True, True, True])
        self.assertLessEqual(report["stages"][3]["p95_ms"], 500)  # numbers are kept, only flagged

    def test_dropped_iterations_make_that_stage_invalid_and_saturated_not_the_run(self):
        report = self.report(synthetic(drops={2: 50}))
        stage = report["stages"][2]
        self.assertEqual(stage["dropped_iterations"], 50)
        self.assertAlmostEqual(stage["dropped_ratio"], 50 / 150, places=5)
        self.assertFalse(stage["valid"])
        self.assertEqual(report["limit_rps"], 500)
        self.assertEqual([s["saturated"] for s in report["stages"]], [False, False, True, True, True])
        self.assertTrue(report["validity"]["valid"])

    def test_dropped_iterations_in_the_first_stage_invalidate_the_run(self):
        report = self.report(synthetic(drops={0: 50}))
        self.assertEqual(report["limit_rps"], "below_min_stage")
        self.assertFalse(report["validity"]["stage0_valid"])
        self.assertFalse(report["validity"]["valid"])
        self.assertTrue(all(s["saturated"] for s in report["stages"]))

    def test_less_than_one_percent_dropped_is_still_valid(self):
        report = self.report(synthetic(drops={0: 1}))  # 1 / 101 < 1%
        self.assertTrue(report["stages"][0]["valid"])
        self.assertEqual(report["limit_rps"], "not reached")

    def test_error_rate_above_one_percent_is_a_violation(self):
        lines = synthetic()
        start, _ = window_of("ramping", 1)
        lines += request_lines(start + 1, 10, "measure", 500, "error") * 2  # 2 errors in 102 requests
        report = self.report(lines)
        self.assertAlmostEqual(report["stages"][1]["error_rate"], 2 / 102, places=5)
        self.assertEqual(report["limit_rps"], 100)

    def test_db_mismatch_makes_the_run_invalid(self):
        lines = synthetic()
        report = self.report(lines, db_rows=issued_count(lines) - 1)
        self.assertFalse(report["db"]["match"])
        self.assertFalse(report["validity"]["db_match"])
        self.assertFalse(report["validity"]["valid"])

    def test_filtered_raw_reproduces_the_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            filtered = os.path.join(tmp, "filtered.json.gz")
            noisy = synthetic() + [point("http_req_blocked", 1, 0.1), '{"type":"Metric","data":{},"metric":"http_reqs"}']
            first = self.report(noisy, filtered=filtered)
            self.assertEqual(len(first["_filtered_lines"]), len(synthetic()))
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

    def test_profiles_json_is_well_formed(self):
        with open(os.path.join(HERE, "profiles.json"), encoding="utf-8") as f:
            real = json.load(f)
        for name, profile in real["profiles"].items():
            rates = [s["rps"] for s in profile["steps"]]
            self.assertEqual(len(rates), len(set(rates)), name)
            self.assertTrue(all(s["seconds"] > real["transition_seconds"] for s in profile["steps"]), name)
            self.assertGreater(profile["stock"], 0, name)


class ReprocessTest(unittest.TestCase):
    def make_source(self, tmp, with_layout):
        lines = synthetic(drops={2: 50})
        write_raw(os.path.join(tmp, "raw.json.gz"), lines)
        old = {"test": "B", "profile": "ramping", "stage": "s1", "stock": 10000000,
               "db": {"issued_rows": issued_count(lines), "http_issued": issued_count(lines), "match": True},
               "validity": {"dropped_ratio": 0.5, "valid": False},
               "env": {"app_cpus": 2, "k6_version": "v2.2.0", "cpu": 8, "os": "Darwin 25.3.0 arm64"}}
        if with_layout:
            old["layout"] = {"warmup": LAYOUT["warmup"], "transition_seconds": 1,
                             "steps": LAYOUT["profiles"]["ramping"]["steps"]}
        with open(os.path.join(tmp, "load.json"), "w", encoding="utf-8") as f:
            json.dump(old, f)

    def reprocess(self, with_layout, profiles=None):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_source(tmp, with_layout)
            out = os.path.join(tmp, "new.json")
            argv = ["--reprocess-from", tmp, "--out", out]
            if profiles:
                path = os.path.join(tmp, "profiles.json")
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(profiles, f)
                argv += ["--profiles", path]
            self.assertEqual(postprocess.main(argv), 0)
            with open(out, encoding="utf-8") as f:
                return json.load(f), tmp

    def test_old_report_without_layout_uses_profiles_json(self):
        report, tmp = self.reprocess(False, profiles=LAYOUT)
        self.assertEqual(report["stage"], "s1")
        self.assertEqual(report["limit_rps"], 500)
        self.assertTrue(report["validity"]["valid"])  # judged by the new rule, not the stored verdict
        self.assertEqual(report["reprocessed_from"], tmp)
        self.assertEqual(report["env"], {"app_cpus": 2, "k6_version": "v2.2.0", "cpu": 8, "os": "Darwin 25.3.0 arm64"})
        self.assertEqual(report["db"]["issued_rows"], report["db"]["http_issued"])

    def test_stored_layout_wins_over_profiles_json(self):
        retuned = json.loads(json.dumps(LAYOUT))
        retuned["profiles"]["ramping"]["steps"] = [{"rps": 7, "seconds": 30}]
        report, _ = self.reprocess(True, profiles=retuned)
        self.assertEqual([s["target_rps"] for s in report["stages"]], [100, 500, 1000, 2000, 3000])


if __name__ == "__main__":
    unittest.main()
