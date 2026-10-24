"""Checks recovery_postprocess.py (test D accounting) on synthetic inputs. Run: python3 -I -m unittest discover -s scripts/load -v"""
import datetime
import gzip
import importlib.util
import json
import os
import tempfile
import unittest
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("recovery_postprocess", os.path.join(HERE, "recovery_postprocess.py"))
rp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rp)

BASE = 1_000_000_000.0  # epoch second at which the synthetic run starts; the main (500 rps) phase starts 10 s later
MAIN = BASE + 10
LAYOUT = {"warmup": {"rps": 100, "seconds": 10}, "load": {"rps": 500, "seconds": 60}, "http_timeout_seconds": 5}


def iso(epoch):
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def point(metric, epoch, value, tags):
    return json.dumps({"type": "Point", "metric": metric, "data": {"time": iso(epoch), "value": value, "tags": tags}})


def synthetic_raw(failing=range(20, 23), fail_result="http_5xx", last_sec=59, seqs=None):
    """k6 output of a run: 100 rps in seconds -10..-1, 500 rps in 0..last_sec; every request in `failing` seconds gets `fail_result`."""
    lines = []
    for sec in range(-10, last_sec + 1):
        phase = "warmup" if sec < 0 else "main"
        rate = 100 if sec < 0 else 500
        result = fail_result if sec in failing else "issued"
        lines.append(point("recovery_results", MAIN + sec + 0.5, rate, {"phase": phase, "sec": str(sec), "result": result}))
    for seq in (seqs if seqs is not None else [1, 2, 3, 3]):
        lines.append(point("recovery_seq", MAIN + 1, seq, {"phase": "main", "sec": "1"}))
    lines.append(point("http_req_duration", MAIN + 0.002, 2.0, {"phase": "main", "sec": "0"}))
    lines.append(point("http_req_duration", BASE + 0.002, 2.0, {"phase": "warmup", "sec": "-10"}))  # earlier, but not the main phase
    lines.append(point("dropped_iterations", MAIN + 30, 12, {}))
    lines.append(point("http_reqs", MAIN + 1, 1, {"phase": "main"}))  # a metric the aggregation does not use
    return lines


def write_raw(path, lines):
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def events(stopped=20.0, started=23.0, healthy=30.0, ended=61.0):
    return {"stopped_at": MAIN + stopped, "started_at": MAIN + started, "healthy_at": MAIN + healthy, "k6_ended_at": MAIN + ended}


class AccountingTest(unittest.TestCase):
    def totals(self, http_200, requests=None):
        return {"requests": requests if requests is not None else http_200, "http_200": http_200}

    def test_seats_taken_without_a_confirmation_and_confirmations_without_a_row(self):
        # 31,000 requests reached the counter, 28,500 got a 200, and only 28,000 of those have a row
        acc = rp.accounting("db-down", 10_000_000, self.totals(28_500, 31_000), 31_000, 28_000)
        self.assertEqual(acc["wins"], 31_000)
        self.assertEqual(acc["consumed_but_unconfirmed"], 2_500)
        self.assertEqual(acc["confirmed_but_missing"], 500)
        self.assertEqual(acc["over_issue"], 0)

    def test_rows_without_a_client_confirmation_make_confirmed_but_missing_negative(self):
        acc = rp.accounting("db-down", 10_000_000, self.totals(100), 105, 105)
        self.assertEqual(acc["confirmed_but_missing"], -5)
        self.assertEqual(acc["consumed_but_unconfirmed"], 5)

    def test_an_overshooting_counter_does_not_count_the_losers_as_unconfirmed(self):
        # stock 100, 1,000 requests: the counter reaches 1,000 but only 100 won; the 900 losers got a 409
        acc = rp.accounting("db-down", 100, self.totals(100, 1_000), 1_000, 100)
        self.assertEqual(acc["wins"], 100)
        self.assertEqual(acc["consumed_but_unconfirmed"], 0)
        self.assertEqual(acc["over_issue"], 0)
        self.assertNotIn("over_issue_note", acc)

    def test_over_issue_is_db_rows_above_the_stock(self):
        acc = rp.accounting("db-down", 100, self.totals(130, 200), 200, 130)
        self.assertEqual(acc["over_issue"], 30)

    def test_a_reset_counter_is_not_meaningful(self):
        # redis-restart, stock 20,000: the counter restarted at 0, so wins and consumed_but_unconfirmed cannot be derived from it
        acc = rp.accounting("redis-restart", 20_000, self.totals(30_000), 17_000, 30_000)
        self.assertFalse(acc["redis_counter_meaningful"])
        self.assertIsNone(acc["wins"])
        self.assertIsNone(acc["consumed_but_unconfirmed"])
        self.assertEqual(acc["over_issue"], 10_000)
        self.assertEqual(acc["confirmed_but_missing"], 0)

    def test_a_stage_without_a_counter_has_no_wins(self):
        acc = rp.accounting("db-down", 10_000_000, self.totals(500), None, 480)
        self.assertFalse(acc["redis_counter_meaningful"])
        self.assertIsNone(acc["wins"])
        self.assertIsNone(acc["consumed_but_unconfirmed"])
        self.assertEqual(acc["confirmed_but_missing"], 20)

    def test_over_issue_is_zero_by_construction_when_the_stock_exceeds_the_requests(self):
        acc = rp.accounting("db-down", 10_000_000, self.totals(500, 600), 600, 500)
        self.assertEqual(acc["over_issue"], 0)
        self.assertIn("0 by construction", acc["over_issue_note"])


class DuplicateTest(unittest.TestCase):
    def test_dup_seq_counts_surplus_rows_and_dup_members_counts_members(self):
        lines = ["1\t1", "2\t2", "3\t1", "4\t2", "1\t3", ""]
        self.assertEqual(rp.dup_counts(lines), (2, 1, 5))  # seq 1 and 2 are each used twice; member 1 owns two rows

    def test_a_restarting_seq_duplicates_every_seq_below_the_restart_point(self):
        lines = ["%d\t%d" % (member, seq) for member, seq in enumerate(list(range(1, 101)) + list(range(1, 61)), start=1)]
        dup_seq, dup_members, rows = rp.dup_counts(lines)
        self.assertEqual((dup_seq, dup_members, rows), (60, 0, 160))

    def test_no_rows(self):
        self.assertEqual(rp.dup_counts([]), (0, 0, 0))


class RecoveryTimeTest(unittest.TestCase):
    def test_rows_that_stop_at_the_end_of_the_load_give_zero(self):
        samples = [(100, 0), (101, 5), (102, 10), (103, 10), (104, 10), (105, 10), (106, 10)]
        self.assertEqual(rp.settle(samples), (True, 10, 102))
        self.assertEqual(rp.recovery_times(samples, fault_end=101, load_end=103), (True, 0.0, 1.0))

    def test_a_backlog_still_draining_after_the_load_gives_the_drain_time(self):
        samples = [(100 + i, 10 * i) for i in range(13)] + [(113 + i, 120) for i in range(5)]  # rows reach 120 at t=112
        self.assertEqual(rp.settle(samples), (True, 120, 112))
        settled, after_both, after_fault = rp.recovery_times(samples, fault_end=101, load_end=105)
        self.assertEqual((settled, after_both, after_fault), (True, 7.0, 11.0))

    def test_a_fault_that_ends_after_the_load_is_measured_from_the_fault_end(self):
        samples = [(100, 0), (101, 0), (102, 7), (103, 7), (104, 7), (105, 7), (106, 7)]
        self.assertEqual(rp.recovery_times(samples, fault_end=101, load_end=100)[1:], (1.0, 1.0))
        self.assertEqual(rp.recovery_times(samples, fault_end=99, load_end=100)[1:], (2.0, 3.0))

    def test_four_unchanged_polls_are_not_settled(self):
        samples = [(100, 1), (101, 2), (102, 2), (103, 2), (104, 2)]
        self.assertEqual(rp.recovery_times(samples, 100, 100), (False, None, None))

    def test_a_null_last_sample_is_not_settled(self):
        samples = [(100, 4), (101, 4), (102, 4), (103, 4), (104, 4), (105, None)]
        self.assertEqual(rp.settle(samples), (False, None, None))

    def test_nulls_during_an_outage_do_not_break_settling_afterwards(self):
        samples = [(100, 5), (101, None), (102, None), (103, 6), (104, 6), (105, 6), (106, 6), (107, 6)]
        self.assertEqual(rp.settle(samples), (True, 6, 103))

    def test_no_samples(self):
        self.assertEqual(rp.settle([]), (False, None, None))


class TimelineTest(unittest.TestCase):
    def test_outcomes_and_the_last_db_sample_of_each_second(self):
        per_sec = {0: Counter(issued=500), 2: Counter(http_5xx=500, timeout=3), 3: Counter(sold_out=7)}
        samples = [(1000.2, 3), (1000.9, 4), (1001.1, 5), (1002.9, None), (1065.0, 99)]
        timeline = rp.build_timeline(per_sec, samples, 1000.0, 10, 60)
        by_sec = {entry["sec"]: entry for entry in timeline}
        self.assertEqual(timeline[0]["sec"], -10)
        self.assertEqual(timeline[-1]["sec"], 65)  # extends to the last sample taken after the load
        self.assertEqual(by_sec[0], {"sec": 0, "http_200": 500, "http_409": 0, "http_5xx": 0, "timeouts": 0, "db_rows": 4})
        self.assertEqual(by_sec[1]["db_rows"], 5)
        self.assertEqual(by_sec[2], {"sec": 2, "http_200": 0, "http_409": 0, "http_5xx": 500, "timeouts": 3, "db_rows": None})
        self.assertEqual(by_sec[3]["http_409"], 7)
        self.assertEqual(by_sec[65]["db_rows"], 99)

    def test_queue_samples_add_the_last_depth_of_each_second(self):
        timeline = rp.build_timeline({}, [], 1000.0, 10, 60, [(1000.1, 5), (1000.8, 7), (1002.2, None), (1070.0, 0)])
        by_sec = {entry["sec"]: entry for entry in timeline}
        self.assertEqual((by_sec[0]["queue_depth"], by_sec[1]["queue_depth"], by_sec[2]["queue_depth"]), (7, None, None))
        self.assertEqual(timeline[-1], {"sec": 70, "http_200": 0, "http_409": 0, "http_5xx": 0, "timeouts": 0, "db_rows": None,
                                        "queue_depth": 0})
        self.assertNotIn("queue_depth", rp.build_timeline({}, [], 1000.0, 10, 60)[0])  # stages without a queue

    def test_samples_before_the_warmup_are_left_out(self):
        timeline = rp.build_timeline({}, [(985.0, 0)], 1000.0, 10, 60)
        self.assertEqual((timeline[0]["sec"], timeline[-1]["sec"]), (-10, 59))


class FaultLogTest(unittest.TestCase):
    def test_events_are_read_and_the_first_occurrence_wins(self):
        text = "\n".join([
            "2026-10-24T05:00:00.000Z k6_started epoch=100.500 test starts at epoch 102",
            "2026-10-24T05:00:20.000Z stopped_at epoch=120.250 about to docker stop coupon-lab-load-mysql",
            "garbage line",
            "2026-10-24T05:00:21.000Z stopped_at epoch=121.000 again",
            "2026-10-24T05:00:30.000Z healthy_at epoch=130.125 healthy",
        ])
        self.assertEqual(rp.parse_fault_log(text), {"k6_started": 100.5, "stopped_at": 120.25, "healthy_at": 130.125})

    def test_the_fault_must_be_logged_in_order_and_inside_the_main_phase(self):
        ok = events()
        self.assertTrue(rp.fault_validity(ok, MAIN, 60))
        self.assertFalse(rp.fault_validity({k: v for k, v in ok.items() if k != "healthy_at"}, MAIN, 60))
        self.assertFalse(rp.fault_validity(events(stopped=-3.0, started=-1.0), MAIN, 60))  # injected during the warmup
        self.assertFalse(rp.fault_validity(events(stopped=61.0, started=62.0, healthy=63.0), MAIN, 60))  # after the load
        self.assertFalse(rp.fault_validity(events(stopped=20.0, started=19.0, healthy=30.0), MAIN, 60))  # out of order
        self.assertFalse(rp.fault_validity(ok, None, 60))


class ReportTest(unittest.TestCase):
    def raw(self, **kwargs):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        path = os.path.join(self._tmp.name, "raw.json.gz")
        write_raw(path, synthetic_raw(**kwargs))
        return rp.collect_raw(path)

    def samples(self, final=29_000):
        # rows grow 410/s and stop at `final`; they read as null while MySQL is down (seconds 20..24)
        out = []
        for sec in range(-12, 70):
            rows = None if 20 <= sec <= 24 else min(final, max(0, sec + 12) * 410)  # reaches `final` at second 59, before the load ends
            out.append((MAIN + sec + 0.05, rows))
        return out

    def report(self, raw, ev=None, samples=None, k6_exit=0, db_rows=29_000, redis_counter=30_000, scenario="db-down", stock=10_000_000):
        return rp.build_report(scenario, "s3", stock, LAYOUT, raw, ev if ev is not None else events(),
                               samples if samples is not None else self.samples(), ["%d\t%d" % (i, i) for i in range(1, 6)],
                               db_rows, redis_counter, k6_exit, 20, 3, "coupon-lab-load-mysql", {"k6_mode": "host"})

    def test_collect_raw_counts_every_outcome_and_finds_the_start_of_the_main_phase(self):
        raw = self.raw()
        totals = rp.totals_of(raw["per_sec"], raw["dropped"])
        self.assertEqual(totals, {"requests": 31_000, "http_200": 29_500, "http_409": 0, "http_5xx": 1_500, "timeouts": 0,
                                  "http_other": 0, "dropped_iterations": 12})
        self.assertAlmostEqual(raw["main_start"], MAIN, places=3)
        self.assertEqual(raw["main_max_sec"], 59)
        self.assertEqual(sorted(raw["seq_values"]), [1, 2, 3, 3])

    def test_timeouts_and_other_responses_have_their_own_counters(self):
        raw = self.raw(failing=range(0, 2), fail_result="timeout")
        totals = rp.totals_of(raw["per_sec"])
        self.assertEqual((totals["timeouts"], totals["http_5xx"], totals["http_200"], totals["requests"]), (1_000, 0, 30_000, 31_000))

    def test_the_k6_error_codes_of_requests_without_an_answer_are_counted(self):
        lines = synthetic_raw()
        lines.append(point("recovery_results", MAIN + 21.5, 7, {"phase": "main", "sec": "21", "result": "timeout", "error_code": "1050"}))
        lines.append(point("recovery_results", MAIN + 22.5, 2, {"phase": "main", "sec": "22", "result": "timeout", "error_code": "1210"}))
        lines.append(point("recovery_results", MAIN + 23.5, 4, {"phase": "main", "sec": "23", "result": "timeout", "error_code": "1050"}))
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "raw.json.gz")
            write_raw(path, lines)
            raw = rp.collect_raw(path)
        self.assertEqual(raw["error_codes"], {"1050": 11, "1210": 2})
        report = self.report(raw)
        self.assertEqual(report["error_codes"], {"1050": 11, "1210": 2})
        self.assertEqual(report["totals"]["timeouts"], 13)

    def test_a_run_without_error_codes_reports_an_empty_mapping(self):
        self.assertEqual(self.report(self.raw())["error_codes"], {})

    def test_a_complete_run_with_the_fault_observed_is_valid_and_carries_the_accounting(self):
        report = self.report(self.raw())
        self.assertEqual(report["validity"], {"k6_completed": True, "fault_observed": True, "valid": True})
        self.assertEqual(report["consumed_but_unconfirmed"], 500)  # 30,000 wins, 29,500 confirmed
        self.assertEqual(report["confirmed_but_missing"], 500)  # 29,500 confirmed, 29,000 rows
        self.assertEqual(report["over_issue"], 0)
        self.assertEqual(report["dup_seq"], 0)
        self.assertEqual(report["seq"], {"confirmed": 4, "distinct": 3, "max": 3})
        self.assertEqual(report["fault"]["observed"]["at_s"], 20.0)
        self.assertEqual(report["fault"]["observed"]["duration_s"], 3.0)
        self.assertTrue(report["settled"])
        self.assertEqual(report["recovery_time_s"], 0.0)  # rows stopped growing when the load ended
        self.assertEqual((report["timeline"][0]["sec"], report["timeline"][-1]["sec"]), (-10, 69))  # warmup .. last sample after the load

    def test_a_failing_k6_makes_the_run_invalid(self):
        self.assertFalse(self.report(self.raw(), k6_exit=99)["validity"]["valid"])

    def test_a_load_that_stopped_early_makes_the_run_invalid(self):
        report = self.report(self.raw(last_sec=40))
        self.assertFalse(report["validity"]["k6_completed"])
        self.assertTrue(report["validity"]["fault_observed"])
        self.assertFalse(report["validity"]["valid"])

    def test_a_fault_that_was_not_logged_makes_the_run_invalid_but_keeps_the_numbers(self):
        report = self.report(self.raw(), ev={"k6_ended_at": MAIN + 61.0})
        self.assertFalse(report["validity"]["fault_observed"])
        self.assertFalse(report["validity"]["valid"])
        self.assertEqual(report["totals"]["http_200"], 29_500)

    def test_loss_never_decides_validity(self):
        report = self.report(self.raw(), db_rows=1_000, redis_counter=29_500)
        self.assertEqual(report["confirmed_but_missing"], 28_500)
        self.assertTrue(report["validity"]["valid"])

    def test_rows_that_keep_arriving_after_the_load_give_a_positive_recovery_time(self):
        samples = [(MAIN + sec + 0.05, min(29_000, (sec + 12) * 400)) for sec in range(-12, 90)]
        report = self.report(self.raw(), samples=samples, ev=events(ended=50.0))  # load "ended" at 50: rows grow until they hit 29,000 (sec 60.5)
        self.assertTrue(report["settled"])
        self.assertAlmostEqual(report["recovery_time_s"], 11.05, places=2)  # 61.05 (first sample at 29,000) - 50
        self.assertGreater(report["rows_final_after_fault_end_s"], report["recovery_time_s"])

    def test_rows_that_never_settle_leave_the_recovery_time_null(self):
        samples = [(MAIN + sec + 0.05, sec * 10) for sec in range(0, 70)]
        report = self.report(self.raw(), samples=samples)
        self.assertFalse(report["settled"])
        self.assertIsNone(report["recovery_time_s"])
        self.assertIsNone(report["rows_final_after_fault_end_s"])

    def test_the_restart_scenario_reports_no_duration_and_an_unusable_counter(self):
        report = self.report(self.raw(), scenario="redis-restart", stock=20_000, db_rows=29_500, redis_counter=9_000)
        self.assertFalse(report["redis_counter_meaningful"])
        self.assertIsNone(report["wins"])
        self.assertEqual(report["over_issue"], 9_500)


    def test_consumer_redeliver_has_no_fault_and_is_valid_on_k6_alone(self):
        raw = self.raw(failing=())
        samples = [(MAIN + sec + 0.05, min(30_000, max(0, sec + 12) * 400)) for sec in range(-12, 90)]
        queue = [(MAIN + sec + 0.1, max(0, 300 - 10 * (sec - 60)) if sec >= 60 else 40) for sec in range(-10, 90)]
        report = rp.build_report("consumer-redeliver", "s4", 10_000_000, LAYOUT, raw, {"k6_ended_at": MAIN + 61.0}, samples,
                                 ["%d\t%d" % (i, i) for i in range(1, 6)], 30_000, 30_000, 0, 20, 10, "coupon-lab-load-app",
                                 {}, settle_s=12.0, queue_samples=queue, redeliveries=600)
        self.assertEqual(report["validity"], {"k6_completed": True, "fault_observed": None, "valid": True})
        self.assertEqual((report["fault"]["at_s"], report["fault"]["duration_s"]), (None, None))
        self.assertEqual((report["wins"], report["seats_lost"], report["dup_seq"]), (30_000, 0, 0))
        self.assertTrue(report["settled"])
        self.assertAlmostEqual(report["recovery_time_s"], 2.05, places=2)  # rows reach 30,000 at 63.05, the load ended at 61
        self.assertEqual((report["settle_s"], report["queue_depth_max"], report["redeliveries"]), (12.0, 300, 600))
        self.assertIn("queue_depth", report["timeline"][0])
        self.assertFalse(self.report(raw, k6_exit=1, scenario="consumer-redeliver")["validity"]["valid"])

    def test_without_queue_samples_or_a_counter_the_s4_fields_are_null(self):
        report = self.report(self.raw())
        self.assertEqual((report["settle_s"], report["queue_depth_max"], report["redeliveries"]), (None, None, None))
        self.assertNotIn("queue_depth", report["timeline"][0])

    def test_the_report_carries_the_loss_axes_and_the_logged_insert_failures(self):
        report = self.report(self.raw())
        self.assertEqual(report["seats_lost"], 1_000)  # 30,000 wins, 29,000 rows
        self.assertEqual(report["client_unknown"], 1_500)  # the three failing seconds of the synthetic run, 500 each
        self.assertEqual(report["recorded_despite_unknown"], -500)  # 29,000 rows, 29,500 confirmed
        self.assertIsNone(report["insert_failures_logged"])
        raw = self.raw()
        with_log = rp.build_report("db-down", "s3", 10_000_000, LAYOUT, raw, events(), self.samples(), [], 29_000, 30_000, 0,
                                   20, 3, "coupon-lab-load-mysql", {}, insert_failures=10)
        self.assertEqual(with_log["insert_failures_logged"], 10)

    def test_reprocess_adds_the_axes_to_an_old_report_and_leaves_the_source_alone(self):
        full = self.report(self.raw())
        old = {k: v for k, v in full.items()
               if k not in ("seats_lost", "client_unknown", "recorded_despite_unknown", "not_admitted", "blackout_s", "legacy_fields",
                            "insert_failures_logged", "error_codes")}
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "recovery.json")
            with open(source, "w", encoding="utf-8") as f:
                json.dump(old, f)
            with open(source, "rb") as f:
                before = f.read()
            again = rp.reprocess(tmp)
            with open(source, "rb") as f:
                self.assertEqual(f.read(), before)
        self.assertEqual(again["seats_lost"], full["seats_lost"])
        self.assertEqual(again["client_unknown"], full["client_unknown"])
        self.assertEqual(again["recorded_despite_unknown"], full["recorded_despite_unknown"])
        self.assertEqual(again["not_admitted"], full["not_admitted"])
        self.assertEqual(again["blackout_s"], full["blackout_s"])
        self.assertIsNone(again["insert_failures_logged"])
        self.assertIsNone(again["error_codes"])  # the raw k6 output is not kept, so older runs cannot be filled in
        self.assertEqual(again["reprocessed_from"], tmp)
        self.assertEqual(again["consumed_but_unconfirmed"], old["consumed_but_unconfirmed"])  # recorded values are kept
        self.assertEqual(again["validity"], old["validity"])


class LossAxesTest(unittest.TestCase):
    def report(self, wins, db_rows, http_200, timeouts=0, http_5xx=0, dup_seq=0, dup_members=0, requests=None,
               redis_counter=None, meaningful=None, timeline=None):
        return {"totals": {"http_200": http_200, "timeouts": timeouts, "http_5xx": http_5xx,
                           "requests": requests if requests is not None else http_200 + timeouts + http_5xx},
                "wins": wins, "db_rows": db_rows, "dup_seq": dup_seq, "dup_members": dup_members,
                "redis_counter": redis_counter, "redis_counter_meaningful": meaningful if meaningful is not None else wins is not None,
                "load": {"seconds": 60}, "timeline": timeline if timeline is not None else []}

    @staticmethod
    def seconds(*http_200_per_second, start=0):
        return [{"sec": start + i, "http_200": n} for i, n in enumerate(http_200_per_second)]

    def test_the_numbers_of_the_s3_db_down_run(self):
        # test D, S3, db-down run 1: 28,183 wins, 28,173 rows, 22,475 confirmed, 5,708 timeouts
        axes = rp.loss_axes(self.report(28_183, 28_173, 22_475, timeouts=5_708))
        self.assertEqual((axes["seats_lost"], axes["client_unknown"], axes["recorded_despite_unknown"]), (10, 5_708, 5_698))

    def test_timeouts_and_5xx_both_leave_the_client_not_knowing(self):
        self.assertEqual(rp.loss_axes(self.report(100, 100, 90, timeouts=3, http_5xx=4))["client_unknown"], 7)

    def test_a_reset_counter_gives_no_seats_lost_and_duplicates_give_no_recorded_despite_unknown(self):
        axes = rp.loss_axes(self.report(None, 30_794, 30_794, http_5xx=1, dup_seq=11_057))
        self.assertEqual((axes["seats_lost"], axes["client_unknown"], axes["recorded_despite_unknown"]), (None, 1, None))

    def test_duplicate_members_alone_also_withhold_recorded_despite_unknown(self):
        self.assertIsNone(rp.loss_axes(self.report(10, 10, 9, dup_members=1))["recorded_despite_unknown"])

    def test_a_stage_without_a_counter_still_has_the_client_side_axes(self):
        axes = rp.loss_axes(self.report(None, 480, 500, timeouts=20))
        self.assertEqual((axes["seats_lost"], axes["client_unknown"], axes["recorded_despite_unknown"]), (None, 20, -20))

    def test_not_admitted_is_the_requests_that_never_reached_the_counter(self):
        # test D, S3, 49 s outage: 24,660 requests, the counter reached 15,455
        axes = rp.loss_axes(self.report(15_455, 15_255, 3_533, timeouts=21_127, requests=24_660, redis_counter=15_455))
        self.assertEqual(axes["not_admitted"], 9_205)

    def test_not_admitted_is_null_without_a_usable_counter(self):
        reset = self.report(None, 30_794, 30_794, requests=30_795, redis_counter=19_737, meaningful=False)
        self.assertIsNone(rp.loss_axes(reset)["not_admitted"])
        self.assertIsNone(rp.loss_axes(self.report(None, 5, 5, requests=5))["not_admitted"])

    def test_blackout_is_the_longest_run_of_seconds_without_a_200(self):
        timeline = self.seconds(*([500] * 5 + [0] * 55))  # a 49 s outage: the 200s stop at second 5 and never return during the load
        self.assertEqual(rp.loss_axes(self.report(1, 1, 1, timeline=timeline))["blackout_s"], 55)
        self.assertEqual(rp.blackout_seconds(self.seconds(5, 0, 0, 5, 0, 0, 0, 5), 60), 3)
        self.assertEqual(rp.blackout_seconds(self.seconds(*([500] * 60)), 60), 0)

    def test_blackout_ignores_seconds_after_the_load_and_a_missing_second_breaks_the_run(self):
        self.assertEqual(rp.blackout_seconds(self.seconds(0, 0, 0, start=58), 60), 2)  # seconds 58 and 59 only
        gap = [{"sec": 0, "http_200": 0}, {"sec": 2, "http_200": 0}]
        self.assertEqual(rp.blackout_seconds(gap, 60), 1)

    def test_the_first_generation_fields_are_named_as_legacy(self):
        self.assertEqual(rp.loss_axes(self.report(1, 1, 1))["legacy_fields"], ["consumed_but_unconfirmed", "confirmed_but_missing"])


class CommandLineTest(unittest.TestCase):
    def test_main_writes_recovery_json_and_timeline_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            def at(name):
                return os.path.join(tmp, name)
            write_raw(at("raw.json.gz"), synthetic_raw())
            with open(at("fault.log"), "w", encoding="utf-8") as f:
                for name, epoch in events().items():
                    f.write("%s %s epoch=%.3f note\n" % (iso(epoch), name, epoch))
            with open(at("db-samples.jsonl"), "w", encoding="utf-8") as f:
                for sec in range(-5, 75):
                    f.write(json.dumps({"time": int(MAIN) + sec, "db_rows": min(29_000, max(0, sec) * 500)}) + "\n")
            with open(at("issues.tsv"), "w", encoding="utf-8") as f:
                f.write("1\t1\n2\t2\n3\t2\n")
            argv = ["--raw", at("raw.json.gz"), "--scenario", "db-down", "--stage", "s3", "--stock", "10000000",
                    "--fault-log", at("fault.log"), "--db-samples", at("db-samples.jsonl"), "--issues", at("issues.tsv"),
                    "--db-rows", "29000", "--redis-counter", "30000", "--k6-exit", "0", "--fault-at", "20",
                    "--fault-duration", "3", "--target", "coupon-lab-load-mysql", "--env", "redis_appendonly=no",
                    "--out", at("recovery.json"), "--timeline-out", at("timeline.json")]
            self.assertEqual(rp.main(argv), 0)
            with open(at("recovery.json"), encoding="utf-8") as f:
                report = json.load(f)
            with open(at("timeline.json"), encoding="utf-8") as f:
                timeline = json.load(f)
        self.assertEqual(report["test"], "D")
        self.assertEqual(report["scenario"], "db-down")
        self.assertEqual(report["fault"]["duration_s"], 3)
        self.assertEqual(report["dup_seq"], 1)
        self.assertEqual(report["env"]["redis_appendonly"], "no")
        self.assertEqual(report["timeline"], timeline)
        self.assertTrue(report["validity"]["valid"])

    def test_reprocess_from_writes_a_new_report_and_needs_no_other_arguments(self):
        with tempfile.TemporaryDirectory() as tmp:
            old = {"totals": {"http_200": 10, "timeouts": 2, "http_5xx": 1, "requests": 20}, "wins": 14, "db_rows": 12, "dup_seq": 0,
                   "dup_members": 0, "redis_counter": 15, "redis_counter_meaningful": True, "load": {"seconds": 60},
                   "timeline": [], "validity": {"valid": True}}
            with open(os.path.join(tmp, "recovery.json"), "w", encoding="utf-8") as f:
                json.dump(old, f)
            out = os.path.join(tmp, "new.json")
            self.assertEqual(rp.main(["--reprocess-from", tmp + "/", "--out", out]), 0)
            with open(out, encoding="utf-8") as f:
                new = json.load(f)
        self.assertEqual((new["seats_lost"], new["client_unknown"], new["recorded_despite_unknown"]), (2, 3, 2))
        self.assertEqual((new["not_admitted"], new["blackout_s"]), (5, 0))
        self.assertEqual((new["settle_s"], new["queue_depth_max"], new["redeliveries"]), (None, None, None))

    def test_the_s4_inputs_from_the_command_line_end_up_in_the_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            def at(name):
                return os.path.join(tmp, name)
            write_raw(at("raw.json.gz"), synthetic_raw(failing=()))
            with open(at("fault.log"), "w", encoding="utf-8") as f:
                f.write("%s k6_ended_at epoch=%.3f k6 exit 0\n" % (iso(MAIN + 61), MAIN + 61))
            with open(at("queue.jsonl"), "w", encoding="utf-8") as f:
                for sec, depth in ((0, 3), (1, "null"), (2, 41), (3, 0)):
                    f.write('{"time":%d,"queue_depth":%s}\n' % (int(MAIN) + sec, depth))
                f.write("not json\n")
            for name in ("db-samples.jsonl", "issues.tsv"):
                open(at(name), "w").close()
            argv = ["--raw", at("raw.json.gz"), "--scenario", "consumer-redeliver", "--stage", "s4", "--stock", "10000000",
                    "--fault-log", at("fault.log"), "--db-samples", at("db-samples.jsonl"), "--issues", at("issues.tsv"),
                    "--db-rows", "30000", "--redis-counter", "30000", "--k6-exit", "0", "--fault-at", "20", "--fault-duration", "10",
                    "--target", "coupon-lab-load-app", "--settle-s", "6", "--queue-samples", at("queue.jsonl"), "--redeliveries", "600",
                    "--out", at("recovery.json"), "--timeline-out", at("timeline.json")]
            self.assertEqual(rp.main(argv), 0)
            with open(at("recovery.json"), encoding="utf-8") as f:
                report = json.load(f)
        self.assertEqual((report["settle_s"], report["queue_depth_max"], report["redeliveries"]), (6.0, 41, 600))
        self.assertEqual(report["scenario"], "consumer-redeliver")
        self.assertTrue(report["validity"]["valid"])

    def test_a_fresh_run_without_its_inputs_is_a_usage_error(self):
        with self.assertRaises(SystemExit):
            rp.main(["--scenario", "db-down", "--out", "x.json"])

    def test_insert_failures_from_the_command_line_end_up_in_the_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            def at(name):
                return os.path.join(tmp, name)
            write_raw(at("raw.json.gz"), synthetic_raw())
            with open(at("fault.log"), "w", encoding="utf-8") as f:
                for name, epoch in events().items():
                    f.write("%s %s epoch=%.3f note\n" % (iso(epoch), name, epoch))
            for name in ("db-samples.jsonl", "issues.tsv"):
                open(at(name), "w").close()
            argv = ["--raw", at("raw.json.gz"), "--scenario", "db-down", "--stage", "s3", "--stock", "10000000",
                    "--fault-log", at("fault.log"), "--db-samples", at("db-samples.jsonl"), "--issues", at("issues.tsv"),
                    "--db-rows", "29000", "--redis-counter", "30000", "--k6-exit", "0", "--insert-failures", "10",
                    "--out", at("recovery.json"), "--timeline-out", at("timeline.json")]
            self.assertEqual(rp.main(argv), 0)
            with open(at("recovery.json"), encoding="utf-8") as f:
                self.assertEqual(json.load(f)["insert_failures_logged"], 10)

    def test_profiles_json_has_the_recovery_layout_of_the_spec(self):
        with open(os.path.join(HERE, "profiles.json"), encoding="utf-8") as f:
            recovery = json.load(f)["recovery"]
        self.assertEqual(recovery["warmup"]["rps"], 100)
        self.assertEqual(recovery["warmup"]["seconds"], 10)
        self.assertEqual((recovery["load"]["rps"], recovery["load"]["seconds"]), (500, 60))
        self.assertEqual((recovery["load"]["pre_allocated_vus"], recovery["load"]["max_vus"]), (200, 2000))
        self.assertEqual(recovery["http_timeout_seconds"], 5)


if __name__ == "__main__":
    unittest.main()
