#!/usr/bin/env python3
"""Aggregate one S6 test-D/F run (scripts/verify.sh recovery --stage s6) into recovery.json. Standard library only.

usage: python3 -I recovery_s6_postprocess.py --raw raw.json.gz --scenario db-down|consumer-redeliver|broker-down --stock N
           --fault-log fault.log --db-samples db-samples.jsonl --requests requests.tsv --issue-rows N --dlq-depth N --remaining N
           --k6-exit N --out recovery.json [--queue-samples queue.jsonl] [--metrics '{"name": value, ...}']
           [--settle-s S] [--settled true|false] [--fault-at S] [--fault-duration S] [--env key=value ...]

Inputs written by the runner:
  raw.json.gz      k6 JSON output of recovery_s6.js: recovery_results points tagged phase, sec, result (accepted | unconfirmed |
                   http_5xx | timeout | other), rid (the request id the client sent) and, without an HTTP answer, error_code.
  fault.log        "<iso time> <event> epoch=<seconds> <note>" lines; stopped_at / started_at bound the container fault
                   (db-down: mysql, broker-down: rabbitmq); consumer-redeliver has none.
  db-samples.jsonl {"time": <epoch s>, "db_rows": <coupon_request rows of the event, null while MySQL is unreachable>}, every second.
  requests.tsv     "request_id<TAB>member_id<TAB>status" of every coupon_request row of the event, read after the settle.
  queue.jsonl      {"time", "queue_depth", "dlq_depth", ...} from the management API, about every second.

Definitions:
  sent                       every request of the run (warmup included); accepted / unconfirmed / timeout / http_5xx / other by answer.
  lost                       accepted ids without a coupon_request row: the client was told 202 and the request is gone. MUST be 0.
  recorded_despite_unconfirmed   unconfirmed or timed-out ids that do have a row (the publish reached the queue although the
                             client was not told so): uncertain, not lost; the client retries with the same id.
  unexplained_rows           rows whose request id was never sent by this run. MUST be 0.
  issued / over_issue        ISSUED rows; over_issue = max(0, issued - stock). MUST be 0. issue_rows_match: coupon_issue rows == issued.
  stock_consistent           stock - coupon_event.remaining_quantity == issued: no seat taken twice (a redelivery decrementing again)
                             and none taken without a winner row. MUST hold.
  dup_members                members with more than one ISSUED row. MUST be 0.
  dlq_depth                  messages in coupon.s6.requests.dlq after the settle. MUST be 0 (no poison message is sent).
  blackout_s                 the longest run of consecutive load seconds (0..59) in which no request started in that second got a 202.
  drain_s                    seconds from the later of (fault end, load end) until db_rows reached the value it finally kept.
Validity (the measurement happened as intended): k6 completed, the run settled, and the fault was observed (stopped_at and
started_at for the container scenarios; for consumer-redeliver the app's redelivery counter > 0). PASS = valid and every MUST.
"""
import argparse
import gzip
import json
import re
from collections import Counter

RESULTS = ("accepted", "unconfirmed", "http_5xx", "timeout", "other")


def read_points(path):
    """recovery_results points as (phase, sec, result, rid, error_code)."""
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt") as f:
        for line in f:
            if '"recovery_results"' not in line:
                continue
            point = json.loads(line)
            if point.get("type") != "Point" or point.get("metric") != "recovery_results":
                continue
            tags = point["data"].get("tags", {})
            yield (tags.get("phase"), int(tags.get("sec", "0")), tags.get("result", "other"), tags.get("rid"),
                   tags.get("error_code"))


def read_fault_log(path):
    events = {}
    with open(path) as f:
        for line in f:
            match = re.search(r"^\S+ (\S+) epoch=([0-9.]+)", line)
            if match and match.group(1) not in events:
                events[match.group(1)] = float(match.group(2))
    return events


def read_jsonl(path):
    if not path:
        return []
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def read_requests(path):
    rows = []
    with open(path) as f:
        for line in f:
            fields = line.rstrip("\n").split("\t")
            if len(fields) >= 3 and fields[0]:
                rows.append((fields[0], fields[1], fields[2]))
    return rows


def longest_run(seconds, bad):
    best = run = 0
    for sec in seconds:
        run = run + 1 if bad(sec) else 0
        best = max(best, run)
    return best


def drain_seconds(samples, since):
    """Seconds from `since` until db_rows first reached its final value (None without samples or before `since`)."""
    values = [s for s in samples if s.get("db_rows") is not None]
    if not values or since is None:
        return None
    final = values[-1]["db_rows"]
    reached = None
    for sample in values:
        if sample["db_rows"] == final:
            if reached is None:
                reached = sample["time"]
        else:
            reached = None
    return max(0.0, round(reached - since, 1)) if reached is not None else None


def build(args):
    points = list(read_points(args.raw))
    by_result = {r: set() for r in RESULTS}
    totals = Counter()
    accepted_per_sec = Counter()
    error_codes = Counter()
    for phase, sec, result, rid, error_code in points:
        result = result if result in RESULTS else "other"
        totals[result] += 1
        if rid:
            by_result[result].add(rid)
        if phase == "main" and result == "accepted":
            accepted_per_sec[sec] += 1
        if error_code:
            error_codes[error_code] += 1
    sent = set().union(*by_result.values())

    rows = read_requests(args.requests)
    row_ids = {rid for rid, _, _ in rows}
    status_counts = Counter(status for _, _, status in rows)
    issued_members = Counter(member for _, member, status in rows if status == "ISSUED")
    lost = sorted(by_result["accepted"] - row_ids)
    unconfirmed_or_timeout = by_result["unconfirmed"] | by_result["timeout"] | by_result["http_5xx"]
    recorded_despite = len(unconfirmed_or_timeout & row_ids)
    unexplained = len(row_ids - sent)
    issued = status_counts.get("ISSUED", 0)

    events = read_fault_log(args.fault_log)
    samples = read_jsonl(args.db_samples)
    queue = read_jsonl(args.queue_samples)
    metrics = json.loads(args.metrics) if args.metrics else {}
    fault_end = events.get("started_at")
    load_end = events.get("k6_ended_at")
    since = max(t for t in (fault_end, load_end) if t is not None) if (fault_end or load_end) else None

    if args.scenario == "consumer-redeliver":
        fault_observed = (metrics.get("coupon_s6_redeliveries") or 0) > 0
    else:
        fault_observed = "stopped_at" in events and "started_at" in events

    must = {
        "lost_zero": len(lost) == 0,
        "unexplained_rows_zero": unexplained == 0,
        "over_issue_zero": max(0, issued - args.stock) == 0,
        "dup_members_zero": sum(1 for n in issued_members.values() if n > 1) == 0,
        "issue_rows_match": args.issue_rows == issued,
        "dlq_zero": args.dlq_depth == 0,
        "stock_consistent": args.stock - args.remaining == issued,
    }
    validity = {
        "k6_completed": args.k6_exit == 0,
        "settled": args.settled,
        "fault_observed": fault_observed,
    }
    validity["valid"] = all(validity.values())

    def maximum(key):
        values = [q.get(key) for q in queue if q.get(key) is not None]
        return max(values) if values else None

    return {
        "test": "F" if args.scenario == "broker-down" else "D",
        "scenario": args.scenario,
        "stage": "s6",
        "stock": args.stock,
        "fault": {"at_s": args.fault_at, "duration_s": args.fault_duration,
                  "observed": {k: events.get(k) for k in ("stopped_at", "stop_returned_at", "started_at", "healthy_at")}},
        "totals": dict((r, totals.get(r, 0)) for r in RESULTS),
        "sent": len(sent),
        "rows": len(rows),
        "rows_by_status": dict(sorted(status_counts.items())),
        "lost": len(lost),
        "lost_sample": lost[:20],
        "recorded_despite_unconfirmed": recorded_despite,
        "unexplained_rows": unexplained,
        "issued": issued,
        "over_issue": max(0, issued - args.stock),
        "issue_rows": args.issue_rows,
        "remaining": args.remaining,
        "dup_members": sum(1 for n in issued_members.values() if n > 1),
        "dlq_depth": args.dlq_depth,
        "blackout_s": longest_run(range(0, 60), lambda s: accepted_per_sec.get(s, 0) == 0),
        "error_codes": dict(sorted(error_codes.items())),
        "settle_s": args.settle_s,
        "drain_s": drain_seconds(samples, since),
        "queue_depth_max": maximum("queue_depth"),
        "dlq_depth_max": maximum("dlq_depth"),
        "metrics": metrics,
        "must": must,
        "validity": validity,
        "pass": validity["valid"] and all(must.values()),
        "env": dict(sorted(args.env)),
    }


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", required=True)
    parser.add_argument("--scenario", required=True, choices=("db-down", "consumer-redeliver", "broker-down"))
    parser.add_argument("--stock", type=int, required=True)
    parser.add_argument("--fault-log", required=True)
    parser.add_argument("--db-samples", required=True)
    parser.add_argument("--requests", required=True)
    parser.add_argument("--issue-rows", type=int, required=True)
    parser.add_argument("--dlq-depth", type=int, required=True)
    parser.add_argument("--remaining", type=int, required=True)
    parser.add_argument("--k6-exit", type=int, required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--queue-samples")
    parser.add_argument("--metrics")
    parser.add_argument("--settle-s", type=float)
    parser.add_argument("--settled", choices=("true", "false"), default="true")
    parser.add_argument("--fault-at", type=int)
    parser.add_argument("--fault-duration", type=int)
    parser.add_argument("--env", action="append", default=[], type=lambda kv: tuple(kv.split("=", 1)))
    args = parser.parse_args(argv)
    args.settled = args.settled == "true"
    report = build(args)
    with open(args.out, "w") as f:
        json.dump(report, f, indent=2)
        f.write("\n")
    print("recovery_s6_postprocess: scenario=%s sent=%d accepted=%d lost=%d recorded_despite_unconfirmed=%d over_issue=%d "
          "dup_members=%d dlq=%d valid=%s pass=%s" % (
              report["scenario"], report["sent"], report["totals"]["accepted"], report["lost"],
              report["recorded_despite_unconfirmed"], report["over_issue"], report["dup_members"], report["dlq_depth"],
              str(report["validity"]["valid"]).lower(), str(report["pass"]).lower()))


if __name__ == "__main__":
    main()
