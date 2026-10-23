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
    "vus": {"pre_allocated": 500, "max": 3000},
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


def stat_line(offset_s, name, cpu):
    return json.dumps({"time": int(postprocess.parse_time(stamp(offset_s))),
                       "stat": {"Name": name, "CPUPerc": "%.2f%%" % cpu, "MemUsage": "1GiB / 8GiB"}})


def write_raw(path, lines):
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# A tiny layout whose steps carry enough requests to be classified by achieved_rps (window = 4 s per step).
MINI = {
    "warmup": {"rps": 10, "seconds": 2},
    "transition_seconds": 1,
    "vus": {"pre_allocated": 5, "max": 50},
    "profiles": {"mini": {"stock": 1000, "steps": [{"rps": r, "seconds": 5} for r in (10, 20, 40)]}},
}


def mini_window(index):
    start = MINI["warmup"]["seconds"] + 5 * index
    return start + 1, start + 5


def fast(i, n):
    return 5.0


def with_stalled_tail(i, n):
    """Median fine, the slowest 10% take 2 s: p95 > 500 ms without queueing."""
    return 2000.0 if i >= n * 0.9 else 5.0


def slow(i, n):
    return 300.0


def mini_lines(steps):
    """steps: one dict per step with frac (share of the target rate that completes), lat and drops."""
    lines = [point("http_req_duration", 0.01, 10, status="200", phase="warmup", target_rps="10"),
             point("coupon_results", 0.01, 1, result="issued", phase="warmup", target_rps="10")]
    for index, spec in enumerate(steps):
        rps = MINI["profiles"]["mini"]["steps"][index]["rps"]
        start, end = mini_window(index)
        n = int(round(rps * (end - start) * spec.get("frac", 1.0)))
        lat = spec.get("lat", fast)
        for i in range(n):
            lines += request_lines(start + (end - start) * i / float(n), lat(i, n), "measure", rps, "issued")
        if spec.get("drops"):
            lines.append(point("dropped_iterations", start + 1, spec["drops"]))
    return lines


def gc_pause_line(offset_s, ms, kind="Young (Normal) (G1 Evacuation Pause)"):
    """The line a real JDK 21 prints with -Xlog:gc*:file=...:time,uptime when a pause ends (+0900 as in stamp())."""
    when = (T0 + timedelta(seconds=offset_s)).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "+0900"
    return "[%s][%.3fs] GC(7) Pause %s 18M->10M(64M) %.3fms" % (when, 100 + offset_s, kind, ms)


class GcTest(unittest.TestCase):
    def report(self, gc_lines, extra=None):
        lines = synthetic()
        with tempfile.TemporaryDirectory() as tmp:
            raw = os.path.join(tmp, "raw.json.gz")
            write_raw(raw, lines)
            gc_path = None
            if gc_lines is not None:
                gc_path = os.path.join(tmp, "gc.log")
                with open(gc_path, "w", encoding="utf-8") as f:
                    f.write("\n".join(gc_lines) + "\n")
            return postprocess.build_report(raw, LAYOUT, "ramping", "s0", issued_count(lines), {}, None, None, None,
                                            gc_path, extra)

    def test_real_jvm_log_lines_are_parsed(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "gc.log")
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join([
                    "[2026-10-23T07:49:09.378+0000][1.122s] GC(0) Pause Young (Normal) (G1 Evacuation Pause)",
                    "[2026-10-23T07:49:09.394+0000][1.138s] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 14M->4M(64M) 15.951ms",
                    "[2026-10-23T07:49:10.000+0000][1.700s] GC(1) Concurrent Mark Cycle 40.000ms",
                    "[2026-10-23T07:49:11.500+0000][3.200s] GC(2) Pause Remark 22M->22M(64M) 250.500ms",
                    "[2026-10-23T07:49:12.000+0000][3.700s] GC(3) Pause Full (System.gc()) 30M->5M(64M) 410.042ms"]) + "\n")
            pauses = postprocess.load_gc_pauses(path)
        self.assertEqual([ms for _, ms in pauses], [15.951, 250.5, 410.042])
        self.assertAlmostEqual(pauses[0][0], postprocess.parse_time("2026-10-23T07:49:09.394Z"), places=3)

    def test_pauses_over_200ms_are_counted_in_the_stage_they_fall_into(self):
        start, end = window_of("ramping", 2)
        report = self.report([
            gc_pause_line(start + 3, 450.0),
            gc_pause_line(start + 5, 200.0),  # not over 200
            gc_pause_line(start + 6, 12.0),
            gc_pause_line(end + 0.5, 900.0),  # after the window: belongs to the transition second of the next step
            gc_pause_line(5, 300.0, "Full (System.gc())"),  # warmup: no stage
            "[2026-10-23T03:00:00.000+0000][0.010s] GC(0) Using G1"])
        self.assertEqual([s["gc_pauses_over_200ms"] for s in report["stages"]], [0, 0, 1, 0, 0])

    def test_without_a_gc_log_the_count_is_null(self):
        self.assertTrue(all(s["gc_pauses_over_200ms"] is None for s in self.report(None)["stages"]))

    def test_run_info_is_recorded(self):
        run = {"run_order": 3, "minutes_since_compose_up": 1.4, "container_clock_offset_s": 0}
        self.assertEqual(self.report(None, run)["run"], run)

    def test_the_timestamp_offset_may_omit_the_colon(self):
        self.assertAlmostEqual(postprocess.parse_time("2026-10-23T12:00:00.5+0900"),
                               postprocess.parse_time("2026-10-23T03:00:00.5Z"), places=6)


class KindTest(unittest.TestCase):
    def report(self, steps, stats=None):
        lines = mini_lines(steps)
        with tempfile.TemporaryDirectory() as tmp:
            raw = os.path.join(tmp, "raw.json.gz")
            write_raw(raw, lines)
            stats_path = None
            if stats:
                stats_path = os.path.join(tmp, "stats.jsonl")
                with open(stats_path, "w", encoding="utf-8") as f:
                    f.write("\n".join(stats) + "\n")
            return postprocess.build_report(raw, MINI, "mini", "s0", issued_count(lines), {}, None, stats_path)

    def kinds(self, report):
        return [s["kind"] for s in report["stages"]]

    def test_everything_ok(self):
        report = self.report([{}, {}, {}])
        self.assertEqual(self.kinds(report), ["ok", "ok", "ok"])
        self.assertEqual(report["sustained_limit_rps"], 40)
        self.assertEqual(report["stalls"], [])
        self.assertEqual(report["max_achieved_rps"], {"achieved_rps": 40.0, "target_rps": 40})
        self.assertEqual(report["limit_rps_first_violation"], "not reached")

    def test_a_stall_is_flagged_but_does_not_cap_the_sustained_limit(self):
        start, end = mini_window(1)
        stats = [stat_line(start + 1, "coupon-lab-load-mysql", 101.9), stat_line(start + 2, "coupon-lab-load-app", 75.7)]
        report = self.report([{}, {"lat": with_stalled_tail}, {}], stats=stats)
        self.assertEqual(self.kinds(report), ["ok", "stall", "ok"])
        self.assertEqual(report["sustained_limit_rps"], 40)  # the later ok stage counts
        self.assertEqual(report["limit_rps_first_violation"], 10)  # the older statistic is capped by the stall
        self.assertEqual(report["stalls"], [{"target_rps": 20, "p95_ms": report["stages"][1]["p95_ms"],
                                             "dropped_iterations": 0, "mysql_cpu_pct_max": 101.9,
                                             "app_cpu_pct_max": 75.7}])
        self.assertGreater(report["stalls"][0]["p95_ms"], 500)
        self.assertLess(report["stages"][1]["p50_ms"], 100)

    def test_a_high_median_means_saturated(self):
        report = self.report([{}, {}, {"lat": slow}])
        self.assertEqual(self.kinds(report), ["ok", "ok", "saturated"])
        self.assertEqual(report["sustained_limit_rps"], 20)

    def test_falling_short_of_the_target_rate_means_saturated(self):
        report = self.report([{}, {"frac": 0.9}, {}])  # 18 of 20 rps completed: 90% < 95%
        self.assertEqual(self.kinds(report), ["ok", "saturated", "ok"])

    def test_dropped_iterations_with_fast_latency_are_a_stall(self):
        report = self.report([{}, {"frac": 0.9625, "drops": 3}, {}])  # 77 completed, 3 dropped
        self.assertEqual(self.kinds(report), ["ok", "stall", "ok"])
        self.assertGreaterEqual(report["stages"][1]["dropped_ratio"], 0.01)
        self.assertEqual(report["stalls"][0]["dropped_iterations"], 3)

    def test_max_achieved_may_be_at_a_saturated_stage(self):
        report = self.report([{}, {}, {"frac": 0.7, "lat": slow}])  # 28 rps achieved at target 40
        self.assertEqual(report["max_achieved_rps"], {"achieved_rps": 28.0, "target_rps": 40})
        self.assertEqual(report["sustained_limit_rps"], 20)

    def test_no_ok_stage_gives_no_sustained_limit(self):
        report = self.report([{"lat": slow}, {"lat": slow}, {"lat": slow}])
        self.assertIsNone(report["sustained_limit_rps"])
        # the older statistic only looks at validity, p95 and errors, so it does not see this queueing (p50 300 ms)
        self.assertEqual(report["limit_rps_first_violation"], "not reached")

    def test_the_sustained_limit_ignores_what_lower_stages_did(self):
        report = self.report([{"lat": with_stalled_tail}, {}, {}])
        self.assertEqual(self.kinds(report), ["stall", "ok", "ok"])
        self.assertEqual(report["sustained_limit_rps"], 40)
        self.assertTrue(report["validity"]["valid"])  # run validity is unchanged: stage 0 dropped nothing


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

    def report_with(self, lines, stats=None, summary=None):
        with tempfile.TemporaryDirectory() as tmp:
            raw = os.path.join(tmp, "raw.json.gz")
            write_raw(raw, lines)
            stats_path = summary_path = None
            if stats is not None:
                stats_path = os.path.join(tmp, "stats.jsonl")
                with open(stats_path, "w", encoding="utf-8") as f:
                    f.write("\n".join(stats) + "\n")
            if summary is not None:
                summary_path = os.path.join(tmp, "summary.json")
                with open(summary_path, "w", encoding="utf-8") as f:
                    json.dump(summary, f)
            return postprocess.build_report(raw, LAYOUT, "ramping", "s0", issued_count(lines), {}, None,
                                            stats_path, summary_path)

    def test_cpu_is_summarised_per_stage_window(self):
        start, end = window_of("ramping", 1)  # the 500 rps step
        stats = [stat_line(start + 2, "coupon-lab-load-app", 150.0), stat_line(start + 4, "coupon-lab-load-app", 190.0),
                 stat_line(start + 2, "coupon-lab-load-mysql", 80.5), stat_line(start + 4, "coupon-lab-load-mysql", 99.5),
                 stat_line(5, "coupon-lab-load-app", 5.0),  # during warmup: in no stage window
                 stat_line(end + 1, "coupon-lab-load-app", 5.0),  # after the window
                 "not json", json.dumps({"time": 1, "stat": {"Name": "other", "CPUPerc": "1%"}})]
        report = self.report_with(synthetic(), stats=stats)
        stage = report["stages"][1]
        self.assertEqual((stage["app_cpu_pct_mean"], stage["app_cpu_pct_max"]), (170.0, 190.0))
        self.assertEqual((stage["mysql_cpu_pct_mean"], stage["mysql_cpu_pct_max"]), (90.0, 99.5))
        first = report["stages"][0]
        self.assertEqual((first["app_cpu_pct_mean"], first["mysql_cpu_pct_max"]), (None, None))

    def test_without_stats_cpu_fields_are_null(self):
        stage = self.report_with(synthetic())["stages"][0]
        self.assertIsNone(stage["app_cpu_pct_mean"])
        self.assertIsNone(stage["mysql_cpu_pct_max"])

    def test_vus_max_reached_compares_the_high_water_mark_with_max_vus(self):
        def summary(vus_max, vus):
            return {"metrics": {"vus_max": {"value": vus_max, "min": 500, "max": vus_max},
                                "vus": {"value": 3, "min": 0, "max": vus}}}
        reached = self.report_with(synthetic(), summary=summary(3000, 2900))["vus"]
        self.assertEqual(reached, {"max_vus": 3000, "vus_max": 3000, "vus_peak": 2900, "vus_max_reached": True})
        spare = self.report_with(synthetic(), summary=summary(1200, 1100))["vus"]
        self.assertFalse(spare["vus_max_reached"])
        missing = self.report_with(synthetic())["vus"]
        self.assertEqual(missing, {"max_vus": 3000, "vus_max": None, "vus_peak": None, "vus_max_reached": None})

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
        self.assertEqual(report["limit_rps_first_violation"], "not reached")
        self.assertTrue(report["validity"]["valid"])
        self.assertTrue(all(s["valid"] and not s["after_first_violation"] for s in report["stages"]))
        self.assertEqual(report["db"]["http_issued"], 1 + 5 * 101)  # warmup + measure + transition

    def test_slow_step_is_the_limit_and_everything_after_it_is_saturated(self):
        report = self.report(synthetic(slow_steps={3, 4}))
        self.assertEqual(report["limit_rps_first_violation"], 1000)
        self.assertEqual([s["after_first_violation"] for s in report["stages"]], [False, False, False, True, True])
        self.assertTrue(report["validity"]["valid"])  # saturating later stages does not invalidate the run

    def test_a_good_stage_after_the_first_bad_one_is_still_saturated(self):
        report = self.report(synthetic(slow_steps={2}))
        self.assertEqual(report["limit_rps_first_violation"], 500)
        self.assertEqual([s["after_first_violation"] for s in report["stages"]], [False, False, True, True, True])
        self.assertLessEqual(report["stages"][3]["p95_ms"], 500)  # numbers are kept, only flagged

    def test_dropped_iterations_make_that_stage_invalid_and_saturated_not_the_run(self):
        report = self.report(synthetic(drops={2: 50}))
        stage = report["stages"][2]
        self.assertEqual(stage["dropped_iterations"], 50)
        self.assertAlmostEqual(stage["dropped_ratio"], 50 / 150, places=5)
        self.assertFalse(stage["valid"])
        self.assertEqual(report["limit_rps_first_violation"], 500)
        self.assertEqual([s["after_first_violation"] for s in report["stages"]], [False, False, True, True, True])
        self.assertTrue(report["validity"]["valid"])

    def test_dropped_iterations_in_the_first_stage_invalidate_the_run(self):
        report = self.report(synthetic(drops={0: 50}))
        self.assertEqual(report["limit_rps_first_violation"], "below_min_stage")
        self.assertFalse(report["validity"]["stage0_valid"])
        self.assertFalse(report["validity"]["valid"])
        self.assertTrue(all(s["after_first_violation"] for s in report["stages"]))

    def test_less_than_one_percent_dropped_is_still_valid(self):
        report = self.report(synthetic(drops={0: 1}))  # 1 / 101 < 1%
        self.assertTrue(report["stages"][0]["valid"])
        self.assertEqual(report["limit_rps_first_violation"], "not reached")

    def test_error_rate_above_one_percent_is_a_violation(self):
        lines = synthetic()
        start, _ = window_of("ramping", 1)
        lines += request_lines(start + 1, 10, "measure", 500, "error") * 2  # 2 errors in 102 requests
        report = self.report(lines)
        self.assertAlmostEqual(report["stages"][1]["error_rate"], 2 / 102, places=5)
        self.assertEqual(report["limit_rps_first_violation"], 100)

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
        self.assertGreaterEqual(real["vus"]["max"], real["vus"]["pre_allocated"])


class ReprocessTest(unittest.TestCase):
    def make_source(self, tmp, with_layout):
        lines = synthetic(drops={2: 50})
        write_raw(os.path.join(tmp, "raw.json.gz"), lines)
        old = {"test": "B", "profile": "ramping", "stage": "s1", "stock": 10000000,
               "stages": [{"target_rps": s["rps"]} for s in LAYOUT["profiles"]["ramping"]["steps"]],
               "db": {"issued_rows": issued_count(lines), "http_issued": issued_count(lines), "match": True},
               "validity": {"dropped_ratio": 0.5, "valid": False},
               "env": {"app_cpus": 2, "k6_version": "v2.2.0", "cpu": 8, "os": "Darwin 25.3.0 arm64"}}
        if with_layout:
            old["layout"] = {"warmup": LAYOUT["warmup"], "transition_seconds": 1, "vus": LAYOUT["vus"],
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
        self.assertEqual(report["limit_rps_first_violation"], 500)
        self.assertTrue(report["validity"]["valid"])  # judged by the new rule, not the stored verdict
        self.assertEqual(report["reprocessed_from"], tmp)
        self.assertEqual(report["env"], {"app_cpus": 2, "k6_version": "v2.2.0", "cpu": 8, "os": "Darwin 25.3.0 arm64"})
        self.assertEqual(report["db"]["issued_rows"], report["db"]["http_issued"])

    def test_a_run_without_stored_layout_is_not_reprocessed_with_a_retuned_profile(self):
        retuned = json.loads(json.dumps(LAYOUT))
        retuned["profiles"]["ramping"]["steps"] = [{"rps": r, "seconds": 30} for r in (100, 200, 300)]
        with tempfile.TemporaryDirectory() as tmp:
            self.make_source(tmp, False)
            with open(os.path.join(tmp, "load.json"), encoding="utf-8") as f:
                old = json.load(f)
            old["stages"] = [{"target_rps": r} for r in (100, 500, 1000, 2000, 3000)]
            with open(os.path.join(tmp, "load.json"), "w", encoding="utf-8") as f:
                json.dump(old, f)
            path = os.path.join(tmp, "profiles.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(retuned, f)
            with self.assertRaises(SystemExit):
                postprocess.main(["--reprocess-from", tmp, "--profiles", path, "--out", os.path.join(tmp, "new.json")])
            self.assertFalse(os.path.exists(os.path.join(tmp, "new.json")))

    def test_gc_log_and_run_block_next_to_the_source_are_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_source(tmp, True)
            with open(os.path.join(tmp, "load.json"), encoding="utf-8") as f:
                old = json.load(f)
            old["run"] = {"run_order": 2, "minutes_since_compose_up": 1.1}
            with open(os.path.join(tmp, "load.json"), "w", encoding="utf-8") as f:
                json.dump(old, f)
            start, _ = window_of("ramping", 1)
            with open(os.path.join(tmp, "gc.log"), "w", encoding="utf-8") as f:
                f.write(gc_pause_line(start + 4, 333.0) + "\n")
            out = os.path.join(tmp, "new.json")
            self.assertEqual(postprocess.main(["--reprocess-from", tmp, "--out", out]), 0)
            with open(out, encoding="utf-8") as f:
                report = json.load(f)
        self.assertEqual(report["run"], {"run_order": 2, "minutes_since_compose_up": 1.1})
        self.assertEqual(report["stages"][1]["gc_pauses_over_200ms"], 1)

    def test_stats_and_summary_next_to_the_source_are_used(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_source(tmp, True)
            start, _ = window_of("ramping", 0)
            with open(os.path.join(tmp, "stats.jsonl"), "w", encoding="utf-8") as f:
                f.write(stat_line(start + 3, "coupon-lab-load-app", 120.0) + "\n")
            with open(os.path.join(tmp, "summary.json"), "w", encoding="utf-8") as f:
                json.dump({"metrics": {"vus_max": {"max": 3000}, "vus": {"max": 10}}}, f)
            out = os.path.join(tmp, "new.json")
            self.assertEqual(postprocess.main(["--reprocess-from", tmp, "--out", out]), 0)
            with open(out, encoding="utf-8") as f:
                report = json.load(f)
        self.assertEqual(report["stages"][0]["app_cpu_pct_mean"], 120.0)
        self.assertTrue(report["vus"]["vus_max_reached"])

    def test_stored_layout_wins_over_profiles_json(self):
        retuned = json.loads(json.dumps(LAYOUT))
        retuned["profiles"]["ramping"]["steps"] = [{"rps": 7, "seconds": 30}]
        report, _ = self.reprocess(True, profiles=retuned)
        self.assertEqual([s["target_rps"] for s in report["stages"]], [100, 500, 1000, 2000, 3000])


if __name__ == "__main__":
    unittest.main()
