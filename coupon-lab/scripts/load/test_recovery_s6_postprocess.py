"""Checks recovery_s6_postprocess.py on synthetic inputs. Run: python3 -I -m unittest discover -s scripts/load -v"""
import datetime
import gzip
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("recovery_s6_postprocess", os.path.join(HERE, "recovery_s6_postprocess.py"))
pp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pp)

MAIN = 1_000_000_010.0


def iso(epoch):
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class S6RecoveryPostprocessTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def path(self, name):
        return os.path.join(self.dir, name)

    def run_pp(self, requests, rows, scenario="db-down", stock=1000, issue_rows=None, dlq=0, faults=True, metrics=None,
               k6_exit=0, settled="true", remaining=None):
        """requests: [(sec, result, rid)]; rows: [(rid, member, status)]."""
        with gzip.open(self.path("raw.json.gz"), "wt") as f:
            for sec, result, rid in requests:
                tags = {"phase": "main", "sec": str(sec), "result": result, "rid": rid}
                f.write(json.dumps({"type": "Point", "metric": "recovery_results",
                                    "data": {"time": iso(MAIN + sec), "value": 1, "tags": tags}}) + "\n")
            f.write(json.dumps({"type": "Point", "metric": "http_req_duration", "data": {"time": iso(MAIN), "value": 2}}) + "\n")
        with open(self.path("requests.tsv"), "w") as f:
            for rid, member, status in rows:
                f.write("%s\t%s\t%s\n" % (rid, member, status))
        with open(self.path("fault.log"), "w") as f:
            if faults:
                f.write("2001-09-09T01:46:50.000Z stopped_at epoch=%.3f x\n" % (MAIN + 5))
                f.write("2001-09-09T01:47:35.000Z started_at epoch=%.3f x\n" % (MAIN + 50))
            f.write("2001-09-09T01:47:45.000Z k6_ended_at epoch=%.3f k6 exit 0\n" % (MAIN + 60))
        with open(self.path("db.jsonl"), "w") as f:
            for t, n in ((MAIN + 59, None), (MAIN + 61, len(rows) - 1), (MAIN + 64, len(rows)), (MAIN + 65, len(rows))):
                f.write(json.dumps({"time": t, "db_rows": n}) + "\n")
        argv = ["--raw", self.path("raw.json.gz"), "--scenario", scenario, "--stock", str(stock),
                "--fault-log", self.path("fault.log"), "--db-samples", self.path("db.jsonl"),
                "--requests", self.path("requests.tsv"),
                "--issue-rows", str(issue_rows if issue_rows is not None else sum(1 for r in rows if r[2] == "ISSUED")),
                "--dlq-depth", str(dlq),
                "--remaining", str(remaining if remaining is not None else stock - sum(1 for r in rows if r[2] == "ISSUED")),
                "--k6-exit", str(k6_exit), "--out", self.path("out.json"), "--settled", settled,
                "--env", "queue_type=classic"]
        if metrics is not None:
            argv += ["--metrics", json.dumps(metrics)]
        pp.main(argv)
        with open(self.path("out.json")) as f:
            return json.load(f)

    def test_every_accepted_request_with_a_row_passes(self):
        report = self.run_pp([(0, "accepted", "a"), (1, "accepted", "b")], [("a", "1", "ISSUED"), ("b", "2", "SOLD_OUT")], stock=1)
        self.assertTrue(report["pass"])
        self.assertEqual(0, report["lost"])
        self.assertEqual({"ISSUED": 1, "SOLD_OUT": 1}, report["rows_by_status"])
        self.assertEqual({"queue_type": "classic"}, report["env"])

    def test_an_accepted_request_without_a_row_is_lost_and_fails(self):
        report = self.run_pp([(0, "accepted", "a"), (1, "accepted", "b")], [("a", "1", "ISSUED")])
        self.assertEqual(1, report["lost"])
        self.assertEqual(["b"], report["lost_sample"])
        self.assertFalse(report["must"]["lost_zero"])
        self.assertFalse(report["pass"])

    def test_unconfirmed_or_timed_out_requests_with_a_row_are_uncertain_not_lost(self):
        report = self.run_pp([(0, "accepted", "a"), (1, "unconfirmed", "b"), (2, "timeout", "c"), (3, "timeout", "d")],
                             [("a", "1", "ISSUED"), ("b", "2", "ISSUED"), ("c", "3", "ISSUED")])
        self.assertEqual(0, report["lost"])
        self.assertEqual(2, report["recorded_despite_unconfirmed"])
        self.assertTrue(report["pass"])

    def test_over_issue_duplicate_winners_rows_from_nowhere_and_dead_letters_each_fail(self):
        base = [(0, "accepted", "a"), (0, "accepted", "b")]
        self.assertFalse(self.run_pp(base, [("a", "1", "ISSUED"), ("b", "2", "ISSUED")], stock=1)["must"]["over_issue_zero"])
        self.assertFalse(self.run_pp(base, [("a", "1", "ISSUED"), ("b", "1", "ISSUED")])["must"]["dup_members_zero"])
        self.assertFalse(self.run_pp(base, [("a", "1", "ISSUED"), ("b", "2", "SOLD_OUT"), ("z", "3", "SOLD_OUT")])
                         ["must"]["unexplained_rows_zero"])
        self.assertFalse(self.run_pp(base, [("a", "1", "ISSUED"), ("b", "2", "SOLD_OUT")], dlq=1)["must"]["dlq_zero"])
        self.assertFalse(self.run_pp(base, [("a", "1", "ISSUED"), ("b", "2", "SOLD_OUT")], issue_rows=2)["must"]["issue_rows_match"])

    def test_a_seat_taken_twice_or_without_a_winner_breaks_stock_consistency(self):
        rows = [("a", "1", "ISSUED"), ("b", "2", "SOLD_OUT")]
        self.assertTrue(self.run_pp([(0, "accepted", "a"), (0, "accepted", "b")], rows, stock=10)["must"]["stock_consistent"])
        report = self.run_pp([(0, "accepted", "a"), (0, "accepted", "b")], rows, stock=10, remaining=8)
        self.assertFalse(report["must"]["stock_consistent"])
        self.assertFalse(report["pass"])

    def test_blackout_is_the_longest_run_of_load_seconds_without_a_202(self):
        requests = [(s, "accepted", "r%d" % s) for s in range(60) if not 10 <= s < 25]
        requests += [(s, "unconfirmed", "u%d" % s) for s in range(10, 25)]
        rows = [("r%d" % s, str(s), "SOLD_OUT") for s in range(60) if not 10 <= s < 25]
        self.assertEqual(15, self.run_pp(requests, rows)["blackout_s"])

    def test_drain_is_measured_from_the_later_of_fault_end_and_load_end(self):
        report = self.run_pp([(0, "accepted", "a"), (1, "accepted", "b")], [("a", "1", "ISSUED"), ("b", "2", "SOLD_OUT")])
        self.assertEqual(4.0, report["drain_s"])  # final value first seen at main+64, load ended at main+60

    def test_validity_needs_the_fault_and_k6_and_the_settle(self):
        rows = [("a", "1", "ISSUED")]
        self.assertFalse(self.run_pp([(0, "accepted", "a")], rows, faults=False)["validity"]["valid"])
        self.assertFalse(self.run_pp([(0, "accepted", "a")], rows, k6_exit=99)["validity"]["valid"])
        self.assertFalse(self.run_pp([(0, "accepted", "a")], rows, settled="false")["validity"]["valid"])
        redeliver = self.run_pp([(0, "accepted", "a")], rows, scenario="consumer-redeliver", faults=False,
                                metrics={"coupon_s6_redeliveries": 3})
        self.assertTrue(redeliver["validity"]["fault_observed"])
        self.assertFalse(self.run_pp([(0, "accepted", "a")], rows, scenario="consumer-redeliver", faults=False,
                                     metrics={"coupon_s6_redeliveries": 0})["validity"]["fault_observed"])


if __name__ == "__main__":
    unittest.main()
