#!/usr/bin/env python3
"""Aggregate the k6 JSON output of issue.js into load.json (test B). Standard library only.

usage: python3 -I postprocess.py --raw raw.json.gz --profile ramping|spike --stage sN --db-issued-rows N
           --out load.json [--filtered-raw out.json.gz] [--env key=value ...]

Only the measure phase is aggregated, per target_rps. A request belongs to the step it started in (tag set by
issue.js). Dropped iterations carry no tags, so they are assigned to a step by time, using the step layout of
profiles.json anchored at the start of the first request.
--filtered-raw keeps just the points this script reads, which is what goes into evidence (the full k6 output
has a dozen http metrics per request and runs to a gigabyte).
"""
import argparse
import calendar
import gzip
import json
import math
import os
import platform
import re
import sys
from contextlib import ExitStack

P95_LIMIT_MS = 500.0
ERROR_RATE_LIMIT = 0.01
DROPPED_RATIO_LIMIT = 0.01
WANTED = ('"http_req_duration"', '"coupon_results"', '"dropped_iterations"')
DEFAULT_PROFILES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "profiles.json")

_TIME = re.compile(r"^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)$")


def parse_time(text):
    """RFC 3339 with up to nanosecond digits (older Python's fromisoformat cannot read that) -> epoch seconds."""
    m = _TIME.match(text)
    if not m:
        raise ValueError("unsupported timestamp: " + text)
    year, month, day, hour, minute, second = (int(m.group(i)) for i in range(1, 7))
    epoch = calendar.timegm((year, month, day, hour, minute, second, 0, 0, 0))
    if m.group(7):
        epoch += float("0." + m.group(7))
    zone = m.group(8)
    if zone != "Z":
        sign = 1 if zone[0] == "+" else -1
        epoch -= sign * (int(zone[1:3]) * 3600 + int(zone[4:6]) * 60)
    return epoch


def percentile(sorted_values, q):
    if not sorted_values:
        return None
    k = (len(sorted_values) - 1) * q / 100.0
    low = int(math.floor(k))
    high = min(low + 1, len(sorted_values) - 1)
    return sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (k - low)


def open_text(path, mode):
    if path.endswith(".gz"):
        return gzip.open(path, mode + "t", encoding="utf-8")
    return open(path, mode, encoding="utf-8")


def collect(raw_path, filtered_path=None):
    latencies = {}  # target_rps -> [http_req_duration ms] of phase=measure
    counts = {}  # (target_rps, phase, result) -> responses
    dropped = []  # (epoch seconds, dropped iterations)
    anchor = None  # start of the earliest request
    with ExitStack() as stack:
        src = stack.enter_context(open_text(raw_path, "r"))
        dst = stack.enter_context(open_text(filtered_path, "w")) if filtered_path else None
        for line in src:
            if not any(name in line for name in WANTED):
                continue
            point = json.loads(line)
            if point.get("type") != "Point":
                continue
            metric = point["metric"]
            data = point["data"]
            tags = data.get("tags") or {}
            when = parse_time(data["time"])
            value = data["value"]
            if dst is not None:
                dst.write(line if line.endswith("\n") else line + "\n")
            if metric == "http_req_duration":
                started = when - value / 1000.0
                anchor = started if anchor is None else min(anchor, started)
                if tags.get("phase") == "measure":
                    latencies.setdefault(int(tags["target_rps"]), []).append(value)
            elif metric == "coupon_results":
                key = (int(tags["target_rps"]), tags["phase"], tags["result"])
                counts[key] = counts.get(key, 0) + int(value)
            else:
                dropped.append((when, value))
    return latencies, counts, dropped, anchor


def violates(stage):
    return (stage["requests"] == 0
            or stage["p95_ms"] is None
            or stage["p95_ms"] > P95_LIMIT_MS
            or stage["error_rate"] > ERROR_RATE_LIMIT)


def build_report(raw_path, layout, profile_name, stage_id, db_issued_rows, env, filtered_path=None):
    profile = layout["profiles"][profile_name]
    rates = [step["rps"] for step in profile["steps"]]
    if len(set(rates)) != len(rates):
        raise ValueError("target rps must be unique within a profile: %s" % rates)
    latencies, counts, dropped, anchor = collect(raw_path, filtered_path)
    if anchor is None:
        raise ValueError("no http_req_duration points in " + raw_path)

    stages = []
    cursor = layout["warmup"]["seconds"]
    for step in profile["steps"]:
        rps = step["rps"]
        window_start = anchor + cursor + layout["transition_seconds"]
        window_end = anchor + cursor + step["seconds"]
        cursor += step["seconds"]

        by_result = dict((r, counts.get((rps, "measure", r), 0)) for r in ("issued", "sold_out", "error"))
        requests = sum(by_result.values())
        values = sorted(latencies.get(rps, []))
        stages.append({
            "target_rps": rps,
            "requests": requests,
            "achieved_rps": round(requests / (window_end - window_start), 1),
            "p50_ms": _round(percentile(values, 50)),
            "p95_ms": _round(percentile(values, 95)),
            "p99_ms": _round(percentile(values, 99)),
            "issued": by_result["issued"],
            "sold_out": by_result["sold_out"],
            "errors": by_result["error"],
            "error_rate": round(by_result["error"] / requests, 6) if requests else None,
            "dropped_iterations": int(sum(v for t, v in dropped if window_start <= t < window_end)),
        })

    limit_rps = "not reached"
    for index, stage in enumerate(stages):
        if violates(stage):
            limit_rps = stages[index - 1]["target_rps"] if index > 0 else "below_min_stage"
            break

    measured = sum(s["requests"] for s in stages)
    dropped_total = sum(s["dropped_iterations"] for s in stages)
    dropped_ratio = dropped_total / (measured + dropped_total) if measured + dropped_total else 1.0
    http_issued = sum(n for (_, _, result), n in counts.items() if result == "issued")
    match = db_issued_rows == http_issued

    return {
        "test": "B",
        "profile": profile_name,
        "stage": stage_id,
        "stock": profile["stock"],
        "stages": stages,
        "limit_rps": limit_rps,
        "db": {"issued_rows": db_issued_rows, "http_issued": http_issued, "match": match},
        "validity": {
            "dropped_ratio": round(dropped_ratio, 6),
            "valid": bool(dropped_ratio < DROPPED_RATIO_LIMIT and match),
        },
        "env": env,
    }


def _round(value):
    return None if value is None else round(value, 2)


def _env(pairs):
    env = {}
    for pair in pairs:
        key, _, value = pair.partition("=")
        env[key] = int(value) if value.isdigit() else value
    env["cpu"] = os.cpu_count()
    env["os"] = "%s %s %s" % (platform.system(), platform.release(), platform.machine())
    return env


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--raw", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--db-issued-rows", required=True, type=int)
    parser.add_argument("--out", required=True)
    parser.add_argument("--filtered-raw")
    parser.add_argument("--profiles", default=DEFAULT_PROFILES)
    parser.add_argument("--env", action="append", default=[], metavar="KEY=VALUE")
    args = parser.parse_args(argv)

    with open(args.profiles, encoding="utf-8") as f:
        layout = json.load(f)
    if args.profile not in layout["profiles"]:
        parser.error("unknown profile: " + args.profile)
    report = build_report(args.raw, layout, args.profile, args.stage, args.db_issued_rows,
                          _env(args.env), args.filtered_raw)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
        f.write("\n")
    print("postprocess: profile=%s stage=%s limit_rps=%s valid=%s" % (
        report["profile"], report["stage"], report["limit_rps"], str(report["validity"]["valid"]).lower()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
