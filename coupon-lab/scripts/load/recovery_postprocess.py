#!/usr/bin/env python3
"""Aggregate one test-D run (scripts/verify.sh recovery) into recovery.json and timeline.json. Standard library only.

usage: python3 -I recovery_postprocess.py --raw raw.json.gz --scenario db-down|redis-restart|consumer-redeliver --stage sN --stock N
           --fault-log fault.log --db-samples db-samples.jsonl --issues issues.tsv --db-rows N [--redis-counter N]
           --k6-exit N --out recovery.json --timeline-out timeline.json [--fault-at S] [--fault-duration S]
           [--profiles profiles.json] [--insert-failures N] [--settle-s S] [--queue-samples queue.jsonl] [--redeliveries N]
           [--env key=value ...]
       python3 -I recovery_postprocess.py --reprocess-from <evidence dir> --out recovery.json

Inputs written by the runner:
  raw.json.gz      k6 JSON output of recovery.js (recovery_results by result, recovery_seq, http_req_duration, dropped_iterations).
  fault.log        one line per event: "<iso time> <event> epoch=<seconds> <note>"; events stopped_at (the fault is injected: just
                   before docker stop / docker restart), stop_returned_at (db-down: docker stop returned, MySQL is down),
                   started_at (the fault ended: docker start issued, or docker restart returned), healthy_at (healthcheck
                   healthy again), k6_ended_at. --fault-duration is the time between stop_returned_at and docker start.
  db-samples.jsonl {"time": <epoch s>, "db_rows": <count of coupon_issue rows of the event, null while MySQL is unreachable>}, every second.
  issues.tsv       "member_id<TAB>seq" for every coupon_issue row of the event, read after the run settled.
  queue.jsonl      (S4) {"time": <epoch s>, "queue_depth": <messages of coupon.issued, ready + unacked; null when unreadable>}, about every second.

consumer-redeliver (S4) injects no container fault: the app's consumer throws after every 50th new row, before the ack. There are
no fault events, so validity is k6_completed alone (fault_observed is null) and recovery_time_s is measured from the load end.

Definitions (the numbers are OUTCOMES: they never decide PASS/FAIL, only validity does):
  totals                     every request of the run, warmup included, counted by the result its client saw.
  wins                       min(redis_counter, stock); null when the counter is not meaningful: no counter (stage without one), or the
                             scenario reset it (redis-restart).
  consumed_but_unconfirmed   wins - http_200: seats the decision took that no client saw confirmed (counter <= stock: redis_counter - http_200).
  confirmed_but_missing      http_200 - db_rows: clients told "issued" with no DB row. Negative = rows without a client confirmation
                             (for example the client timed out but the INSERT finished later).
  over_issue                 max(0, db_rows - stock). Bounded by stock only: when the stock exceeds the requests sent it is 0 by construction.
  dup_seq                    rows - distinct seq among the event's rows (surplus rows that share a seq with another row).
  dup_members                members that own more than one row.
  recovery_time_s            seconds from the later of (fault end = started_at, load end = k6_ended_at) until db_rows reached the value it
                             finally kept; "settled" needs 5 consecutive unchanged non-null polls. Rows still arriving after the load ended
                             (a backlog being drained) make it positive. rows_final_after_fault_end_s is the same measured from the fault end alone.
  not_admitted               requests - redis_counter: requests that never reached the decision (the counter). null when the counter is
                             not meaningful. client_unknown minus not_admitted is roughly "took a seat, client does not know".
  blackout_s                 the longest run of consecutive seconds of the load (0..59) in which no request started in that second
                             was answered 200 (a second is counted by when its requests started).
  error_codes                {k6 error_code: requests} of the requests that got no HTTP answer; null when the run predates the
                             recording (the raw k6 output is not kept, so older runs cannot be filled in).
  seats_lost                 wins - db_rows: seats the decision took that have no row (the durability figure). null when wins is null.
  client_unknown             timeouts + http_5xx: requests whose client did not learn the outcome.
  recorded_despite_unknown   db_rows - http_200: rows no client saw confirmed ("unknown to the client, but it did win"); null when
                             duplicates exist (dup_seq or dup_members > 0), because surplus rows are then not such wins. It is
                             confirmed_but_missing with the sign reversed.
  consumed_but_unconfirmed, confirmed_but_missing   the first-generation figures, kept as derived (legacy) fields; see legacy_fields.
  insert_failures_logged     ERROR lines "coupon_issue insert failed after winning the counter" in the app log (S3); null when no log was read.
  settle_s                   seconds the runner waited after the load for db_rows to stop changing (the 5-poll window included); null
                             when not recorded.
  queue_depth_max            the largest queue.jsonl sample (S4); null without samples. The timeline then carries queue_depth per second
                             (the last sample taken in that second).
  redeliveries               the app's coupon_s4_redeliveries counter (messages the broker delivered again) read after the settle; null
                             when not read (stages without a consumer).
  validity                   k6_completed (k6 exited 0 and the main phase ran to its last second) and fault_observed (the fault events were logged,
                             in order, and the fault was injected while the main phase was running; null for consumer-redeliver).
Requests are bucketed by the second they started in, counted from the start of the main phase (the warmup is seconds -10..-1).
"""
import argparse
import importlib.util
import json
import math
import os
import platform
import re
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
SETTLE_POLLS = 5
RESULTS = ("issued", "sold_out", "http_5xx", "timeout", "other")
WANTED = ("http_req_duration", "recovery_results", "recovery_seq", "dropped_iterations")
FAULT_EVENT = re.compile(r"^\S+ (\w+) epoch=([0-9]+(?:\.[0-9]+)?)")


def _postprocess():
    spec = importlib.util.spec_from_file_location("postprocess", os.path.join(HERE, "postprocess.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_fault_log(text):
    """fault.log -> {event: epoch seconds}; the first occurrence of an event wins, other lines are ignored."""
    events = {}
    for line in text.splitlines():
        m = FAULT_EVENT.match(line)
        if m and m.group(1) not in events:
            events[m.group(1)] = float(m.group(2))
    return events


def collect_raw(raw_path):
    """k6 output -> per-second outcome counts, confirmed seqs, dropped iterations and the start of the main phase."""
    pp = _postprocess()
    per_sec = {}  # sec -> Counter(result)
    error_codes = Counter()
    seq_values = []
    dropped = 0
    main_start = None  # epoch of the earliest request of the main phase
    main_max_sec = None
    points = 0
    with pp.open_text(raw_path, "r") as src:
        for line in src:
            if not any(name in line for name in WANTED):
                continue
            point = json.loads(line)
            if point.get("type") != "Point":
                continue
            metric = point["metric"]
            data = point["data"]
            tags = data.get("tags") or {}
            value = data["value"]
            if metric == "recovery_results":
                points += 1
                sec = int(tags["sec"])
                per_sec.setdefault(sec, Counter())[tags["result"]] += int(value)
                if tags.get("error_code"):
                    error_codes[tags["error_code"]] += int(value)
                if tags.get("phase") == "main" and (main_max_sec is None or sec > main_max_sec):
                    main_max_sec = sec
            elif metric == "recovery_seq":
                seq_values.append(int(value))
            elif metric == "dropped_iterations":
                dropped += int(value)
            elif metric == "http_req_duration" and tags.get("phase") == "main":
                started = pp.parse_time(data["time"]) - value / 1000.0
                main_start = started if main_start is None else min(main_start, started)
    return {"per_sec": per_sec, "seq_values": seq_values, "dropped": dropped, "main_start": main_start,
            "main_max_sec": main_max_sec, "points": points, "error_codes": dict(error_codes)}


def totals_of(per_sec, dropped=0):
    counts = Counter()
    for by_result in per_sec.values():
        counts.update(by_result)
    return {"requests": sum(counts[r] for r in RESULTS), "http_200": counts["issued"], "http_409": counts["sold_out"],
            "http_5xx": counts["http_5xx"], "timeouts": counts["timeout"], "http_other": counts["other"],
            "dropped_iterations": dropped}


def accounting(scenario, stock, totals, redis_counter, db_rows):
    """The loss accounting: pure arithmetic on counts, see the module docstring for the definitions."""
    meaningful = redis_counter is not None and scenario != "redis-restart"
    wins = min(redis_counter, stock) if meaningful else None
    over_issue = max(0, db_rows - stock)
    result = {
        "redis_counter": redis_counter,
        "redis_counter_meaningful": meaningful,
        "db_rows": db_rows,
        "over_issue": over_issue,
        "wins": wins,
        "consumed_but_unconfirmed": wins - totals["http_200"] if meaningful else None,
        "confirmed_but_missing": totals["http_200"] - db_rows,
    }
    if stock >= totals["requests"]:
        result["over_issue_note"] = "stock exceeds the requests sent: over_issue is 0 by construction"
    return result


def loss_axes(report):
    """seats_lost, client_unknown, recorded_despite_unknown (see the docstring), derived from fields already in a report."""
    totals = report["totals"]
    wins = report.get("wins")
    no_duplicates = report["dup_seq"] == 0 and report["dup_members"] == 0
    counter = report.get("redis_counter")
    return {
        "seats_lost": wins - report["db_rows"] if wins is not None else None,
        "client_unknown": totals["timeouts"] + totals["http_5xx"],
        "recorded_despite_unknown": report["db_rows"] - totals["http_200"] if no_duplicates else None,
        "not_admitted": totals["requests"] - counter if report.get("redis_counter_meaningful") and counter is not None else None,
        "blackout_s": blackout_seconds(report["timeline"], report["load"]["seconds"]),
        "legacy_fields": ["consumed_but_unconfirmed", "confirmed_but_missing"],
    }


def blackout_seconds(timeline, load_seconds):
    """Longest run of consecutive seconds 0..load_seconds-1 with no 200 among the requests started in them."""
    longest = current = 0
    by_sec = {entry["sec"]: entry for entry in timeline}
    for sec in range(load_seconds):
        entry = by_sec.get(sec)
        if entry is not None and entry["http_200"] == 0:
            current += 1
            longest = max(longest, current)
        else:
            current = 0
    return longest


def reprocess(source_dir):
    """A saved recovery.json with the loss axes added. Everything else is kept as recorded; the source is not touched."""
    with open(os.path.join(source_dir, "recovery.json"), encoding="utf-8") as f:
        report = json.load(f)
    report.update(loss_axes(report))
    report.setdefault("insert_failures_logged", None)
    report.setdefault("error_codes", None)
    for name in ("settle_s", "queue_depth_max", "redeliveries"):
        report.setdefault(name, None)
    report["reprocessed_from"] = source_dir
    return report


def dup_counts(lines):
    """issues.tsv lines "member<TAB>seq" -> (dup_seq, dup_members, rows)."""
    members = Counter()
    seqs = Counter()
    rows = 0
    for line in lines:
        line = line.strip()
        if not line:
            continue
        member, _, seq = line.partition("\t")
        members[member] += 1
        seqs[seq] += 1
        rows += 1
    return rows - len(seqs), sum(1 for n in members.values() if n > 1), rows


def read_samples(path):
    """db-samples.jsonl -> [(epoch, rows or None)] in time order; unreadable lines are skipped."""
    samples = []
    if not path or not os.path.exists(path):
        return samples
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                item = json.loads(line)
                rows = item["db_rows"]
                samples.append((float(item["time"]), None if rows is None else int(rows)))
            except (ValueError, KeyError, TypeError):
                continue
    samples.sort(key=lambda s: s[0])
    return samples


def settle(samples, polls=SETTLE_POLLS):
    """(settled, final rows, epoch from which the final value was kept). Settled = the last `polls` samples are equal and not null."""
    if not samples or samples[-1][1] is None:
        return False, None, None
    final = samples[-1][1]
    index = len(samples)
    while index > 0 and samples[index - 1][1] == final:
        index -= 1
    kept_since = samples[index][0]
    return len(samples) - index >= polls, final, kept_since


def recovery_times(samples, fault_end, load_end):
    """(settled, recovery_time_s, rows_final_after_fault_end_s); the times are null when the rows never settled."""
    settled, _, kept_since = settle(samples)
    if not settled:
        return False, None, None
    after_both = max(0.0, kept_since - max(fault_end, load_end))
    after_fault = max(0.0, kept_since - fault_end)
    return True, round(after_both, 3), round(after_fault, 3)


def read_queue_samples(path):
    """queue.jsonl -> [(epoch, depth or None)] in time order; None when there is no file (stages without a queue)."""
    if not path or not os.path.exists(path):
        return None
    samples = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                item = json.loads(line)
                depth = item["queue_depth"]
                samples.append((float(item["time"]), None if depth is None else int(depth)))
            except (ValueError, KeyError, TypeError):
                continue
    samples.sort(key=lambda s: s[0])
    return samples


def by_second(samples, main_start):
    """[(epoch, value)] -> {second since the main start: the last value sampled in it}."""
    values = {}
    if main_start is not None:
        for when, value in samples:
            values[int(math.floor(when - main_start))] = value
    return values


def build_timeline(per_sec, samples, main_start, warmup_seconds, load_seconds, queue_samples=None):
    """One entry per second: the outcomes of the requests started in it, the last db_rows sample taken in it and, with queue
    samples (S4), the last queue_depth sample taken in it."""
    rows_by_sec = by_second(samples, main_start)
    depth_by_sec = by_second(queue_samples, main_start) if queue_samples is not None else {}
    low = min([-warmup_seconds] + list(per_sec))
    high = max([load_seconds - 1] + list(per_sec) + list(rows_by_sec) + list(depth_by_sec))
    timeline = []
    for sec in range(low, high + 1):
        counts = per_sec.get(sec, Counter())
        entry = {"sec": sec, "http_200": counts["issued"], "http_409": counts["sold_out"],
                 "http_5xx": counts["http_5xx"], "timeouts": counts["timeout"], "db_rows": rows_by_sec.get(sec)}
        if queue_samples is not None:
            entry["queue_depth"] = depth_by_sec.get(sec)
        timeline.append(entry)
    return timeline


def fault_validity(events, main_start, load_seconds):
    """The fault events exist, are in order, and the fault was injected while the main phase was running."""
    needed = ("stopped_at", "started_at", "healthy_at")
    if main_start is None or any(name not in events for name in needed):
        return False
    stopped, started, healthy = (events[name] for name in needed)
    return 0 <= stopped - main_start < load_seconds and stopped <= started <= healthy


def _env(pairs):
    env = {}
    for pair in pairs:
        key, _, value = pair.partition("=")
        env[key] = int(value) if value.isdigit() else value
    env.setdefault("cpu", os.cpu_count())
    env.setdefault("os", "%s %s %s" % (platform.system(), platform.release(), platform.machine()))
    return env


def build_report(scenario, stage, stock, layout, raw, events, samples, issue_lines, db_rows, redis_counter, k6_exit,
                 fault_at, fault_duration, target, env, insert_failures=None, settle_s=None, queue_samples=None,
                 redeliveries=None):
    no_fault = scenario == "consumer-redeliver"
    if no_fault:
        fault_at = fault_duration = None
    warmup = layout["warmup"]
    load = layout["load"]
    totals = totals_of(raw["per_sec"], raw["dropped"])
    main_start = raw["main_start"]
    load_end = events.get("k6_ended_at")
    if load_end is None and main_start is not None:
        load_end = main_start + load["seconds"]
    fault_end = load_end if no_fault else events.get("started_at")
    if fault_end is not None and load_end is not None:
        settled, recovery_time, after_fault = recovery_times(samples, fault_end, load_end)
    else:
        settled, recovery_time, after_fault = False, None, None
    dup_seq, dup_members, issue_rows = dup_counts(issue_lines)
    timeline = build_timeline(raw["per_sec"], samples, main_start, warmup["seconds"], load["seconds"], queue_samples)

    observed = {name: events.get(name) for name in ("stopped_at", "stop_returned_at", "started_at", "healthy_at")
                if name != "stop_returned_at" or name in events}
    if main_start is not None and observed["stopped_at"] is not None:
        observed["at_s"] = round(observed["stopped_at"] - main_start, 3)
    if observed["stopped_at"] is not None and observed["started_at"] is not None:
        observed["duration_s"] = round(observed["started_at"] - observed["stopped_at"], 3)

    k6_completed = bool(k6_exit == 0 and raw["points"] > 0 and raw["main_max_sec"] is not None
                        and raw["main_max_sec"] >= load["seconds"] - 1)
    fault_observed = None if no_fault else fault_validity(events, main_start, load["seconds"])
    confirmed = raw["seq_values"]
    report = {
        "test": "D",
        "scenario": scenario,
        "stage": stage,
        "fault": {"at_s": fault_at, "duration_s": fault_duration, "target": target, "observed": observed},
        "load": {"rps": load["rps"], "seconds": load["seconds"], "warmup_rps": warmup["rps"],
                 "warmup_seconds": warmup["seconds"], "http_timeout_s": layout["http_timeout_seconds"], "stock": stock},
        "totals": totals,
    }
    report.update(accounting(scenario, stock, totals, redis_counter, db_rows))
    report.update({
        "dup_seq": dup_seq,
        "dup_members": dup_members,
        "issue_rows_read": issue_rows,
        "seq": {"confirmed": len(confirmed), "distinct": len(set(confirmed)), "max": max(confirmed) if confirmed else None},
        "insert_failures_logged": insert_failures,
        "error_codes": raw.get("error_codes"),
        "settled": settled,
        "settle_s": settle_s,
        "recovery_time_s": recovery_time,
        "rows_final_after_fault_end_s": after_fault,
        "queue_depth_max": _max_depth(queue_samples),
        "redeliveries": redeliveries,
        "timeline": timeline,
        "validity": {"k6_completed": k6_completed, "fault_observed": fault_observed,
                     "valid": bool(k6_completed and (no_fault or fault_observed))},
        "env": env,
    })
    report.update(loss_axes(report))
    return report


def _max_depth(queue_samples):
    depths = [depth for _, depth in queue_samples or [] if depth is not None]
    return max(depths) if depths else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--raw")
    parser.add_argument("--scenario", choices=("db-down", "redis-restart", "consumer-redeliver"))
    parser.add_argument("--stage")
    parser.add_argument("--stock", type=int)
    parser.add_argument("--fault-log")
    parser.add_argument("--db-samples")
    parser.add_argument("--issues")
    parser.add_argument("--db-rows", type=int)
    parser.add_argument("--redis-counter", type=int)
    parser.add_argument("--k6-exit", type=int)
    parser.add_argument("--insert-failures", type=int)
    parser.add_argument("--settle-s", type=float)
    parser.add_argument("--queue-samples")
    parser.add_argument("--redeliveries", type=int)
    parser.add_argument("--reprocess-from", metavar="EVIDENCE_DIR")
    parser.add_argument("--fault-at", type=int)
    parser.add_argument("--fault-duration", type=int)
    parser.add_argument("--target")
    parser.add_argument("--profiles", default=os.path.join(HERE, "profiles.json"))
    parser.add_argument("--env", action="append", default=[], metavar="KEY=VALUE")
    parser.add_argument("--out", required=True)
    parser.add_argument("--timeline-out")
    args = parser.parse_args(argv)

    if args.reprocess_from:
        report = reprocess(args.reprocess_from.rstrip("/"))
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
            f.write("\n")
        print("recovery_postprocess: reprocessed %s: seats_lost=%s client_unknown=%s not_admitted=%s blackout_s=%s" % (
            args.reprocess_from, report["seats_lost"], report["client_unknown"], report["not_admitted"], report["blackout_s"]))
        return 0
    required = ("raw", "scenario", "stage", "stock", "fault_log", "db_samples", "issues", "db_rows", "k6_exit", "timeline_out")
    missing = [name for name in required if getattr(args, name) is None]
    if missing:
        parser.error("missing: " + ", ".join("--" + name.replace("_", "-") for name in missing))

    with open(args.profiles, encoding="utf-8") as f:
        layout = json.load(f)["recovery"]
    with open(args.fault_log, encoding="utf-8") as f:
        events = parse_fault_log(f.read())
    with open(args.issues, encoding="utf-8") as f:
        issue_lines = f.read().splitlines()
    report = build_report(args.scenario, args.stage, args.stock, layout, collect_raw(args.raw), events,
                          read_samples(args.db_samples), issue_lines, args.db_rows, args.redis_counter, args.k6_exit,
                          args.fault_at, args.fault_duration if args.scenario == "db-down" else None, args.target,
                          _env(args.env), args.insert_failures, args.settle_s, read_queue_samples(args.queue_samples),
                          args.redeliveries)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
        f.write("\n")
    with open(args.timeline_out, "w", encoding="utf-8") as f:
        json.dump(report["timeline"], f, indent=2)
        f.write("\n")
    totals = report["totals"]
    print("recovery_postprocess: scenario=%s stage=%s requests=%d http_200=%d http_5xx=%d timeouts=%d db_rows=%d "
          "seats_lost=%s client_unknown=%s not_admitted=%s blackout_s=%s recovery_time_s=%s valid=%s" % (
              report["scenario"], report["stage"], totals["requests"], totals["http_200"], totals["http_5xx"],
              totals["timeouts"], report["db_rows"], report["seats_lost"], report["client_unknown"],
              report["not_admitted"], report["blackout_s"], report["recovery_time_s"], str(report["validity"]["valid"]).lower()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
