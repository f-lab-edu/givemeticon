#!/usr/bin/env python3
"""Aggregate the k6 JSON output of issue.js into load.json (test B). Standard library only.

usage: python3 -I postprocess.py --raw raw.json.gz --profile ramping|spike --stage sN --db-issued-rows N
           --out load.json [--filtered-raw out.json.gz] [--env key=value ...]
       python3 -I postprocess.py --reprocess-from <evidence dir> --out load.json

Only the measure phase is aggregated, per target_rps. A request belongs to the step it started in (tag set by
issue.js). Dropped iterations carry no tags, so they are assigned to a step by time, using the step layout of
profiles.json anchored at the start of the first request.

Validity is per stage: dropped_iterations / (requests + dropped_iterations) < 1%. A run is valid when its first stage
is valid and the DB row count matches the 200 responses. limit_rps is the last stage before the first stage that is
invalid, slower than 500 ms at p95 or above 1% errors; that stage and every later one is marked saturated.
--reprocess-from re-aggregates an evidence directory (load.json, raw.json.gz, summary.json, stats.jsonl) with the
current rules; the layout stored in its load.json is used when present, otherwise profiles.json, and only if that
still describes the same stages.

--stats is the stats.jsonl sampled by verify.sh (one {"time": epoch, "stat": <docker stats object>} per container
about every 2 s); every stage gets the mean and max CPU% of the app and mysql containers over its measure window
(100 = one fully used core). --summary is the k6 summary export; it provides the VU high-water mark.
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
DROPPED_RATIO_LIMIT = 0.01  # per stage
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


def stage_ok(stage):
    """A stage that still counts as below the limit: valid, answered, fast enough and (almost) error free."""
    return (stage["valid"]
            and stage["requests"] > 0
            and stage["p95_ms"] is not None
            and stage["p95_ms"] <= P95_LIMIT_MS
            and stage["error_rate"] <= ERROR_RATE_LIMIT)


def load_stats(path):
    """stats.jsonl -> {"app": [(epoch, cpu%)], "mysql": [...]}; lines that cannot be read are skipped."""
    samples = {"app": [], "mysql": []}
    if not path or not os.path.exists(path):
        return samples
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                item = json.loads(line)
                name = item["stat"]["Name"]
                cpu = float(item["stat"]["CPUPerc"].rstrip("%"))
                when = float(item["time"])
            except (ValueError, KeyError, TypeError, AttributeError):
                continue
            role = "app" if name.endswith("-app") else "mysql" if name.endswith("-mysql") else None
            if role:
                samples[role].append((when, cpu))
    return samples


def cpu_summary(samples, window_start, window_end):
    values = [cpu for when, cpu in samples if window_start <= when < window_end]
    if not values:
        return None, None
    return round(sum(values) / len(values), 1), round(max(values), 1)


def read_vus(summary_path, layout):
    """VU high-water mark from the k6 summary export against the configured maxVUs."""
    configured = (layout.get("vus") or {}).get("max")
    reached = peak = peak_active = None
    if summary_path and os.path.exists(summary_path):
        with open(summary_path, encoding="utf-8") as f:
            metrics = json.load(f).get("metrics", {})
        peak = (metrics.get("vus_max") or {}).get("max")
        peak_active = (metrics.get("vus") or {}).get("max")
        if peak is not None and configured is not None:
            reached = peak >= configured
    return {"max_vus": configured, "vus_max": peak, "vus_peak": peak_active, "vus_max_reached": reached}


def build_report(raw_path, layout, profile_name, stage_id, db_issued_rows, env, filtered_path=None,
                 stats_path=None, summary_path=None):
    profile = layout["profiles"][profile_name]
    rates = [step["rps"] for step in profile["steps"]]
    if len(set(rates)) != len(rates):
        raise ValueError("target rps must be unique within a profile: %s" % rates)
    latencies, counts, dropped, anchor = collect(raw_path, filtered_path)
    stats = load_stats(stats_path)
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
        dropped_here = int(sum(v for t, v in dropped if window_start <= t < window_end))
        dropped_ratio = dropped_here / (requests + dropped_here) if requests + dropped_here else 1.0
        app_mean, app_max = cpu_summary(stats["app"], window_start, window_end)
        mysql_mean, mysql_max = cpu_summary(stats["mysql"], window_start, window_end)
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
            "dropped_iterations": dropped_here,
            "dropped_ratio": round(dropped_ratio, 6),
            "valid": dropped_ratio < DROPPED_RATIO_LIMIT,
            "saturated": False,
            "app_cpu_pct_mean": app_mean,
            "app_cpu_pct_max": app_max,
            "mysql_cpu_pct_mean": mysql_mean,
            "mysql_cpu_pct_max": mysql_max,
        })

    limit_rps = "not reached"
    for index, stage in enumerate(stages):
        if not stage_ok(stage):
            limit_rps = stages[index - 1]["target_rps"] if index > 0 else "below_min_stage"
            for later in stages[index:]:
                later["saturated"] = True
            break

    http_issued = sum(n for (_, _, result), n in counts.items() if result == "issued")
    match = db_issued_rows == http_issued

    return {
        "test": "B",
        "profile": profile_name,
        "stage": stage_id,
        "stock": profile["stock"],
        "layout": {
            "warmup": layout["warmup"],
            "transition_seconds": layout["transition_seconds"],
            "vus": layout.get("vus"),
            "steps": profile["steps"],
        },
        "stages": stages,
        "limit_rps": limit_rps,
        "db": {"issued_rows": db_issued_rows, "http_issued": http_issued, "match": match},
        "vus": read_vus(summary_path, layout),
        "validity": {
            "stage0_dropped_ratio": stages[0]["dropped_ratio"],
            "stage0_valid": stages[0]["valid"],
            "db_match": match,
            "valid": bool(stages[0]["valid"] and match),
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
    env.setdefault("cpu", os.cpu_count())
    env.setdefault("os", "%s %s %s" % (platform.system(), platform.release(), platform.machine()))
    return env


def layout_from_report(old):
    """Rebuild a layout from the one stored in a load.json, or None for reports that predate it."""
    stored = old.get("layout")
    if not stored:
        return None
    return {"warmup": stored["warmup"], "transition_seconds": stored["transition_seconds"], "vus": stored.get("vus"),
            "profiles": {old["profile"]: {"stock": old["stock"], "steps": stored["steps"]}}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--raw")
    parser.add_argument("--profile")
    parser.add_argument("--stage")
    parser.add_argument("--db-issued-rows", type=int)
    parser.add_argument("--out", required=True)
    parser.add_argument("--filtered-raw")
    parser.add_argument("--profiles", default=DEFAULT_PROFILES)
    parser.add_argument("--env", action="append", default=[], metavar="KEY=VALUE")
    parser.add_argument("--stats", metavar="STATS_JSONL")
    parser.add_argument("--summary", metavar="SUMMARY_JSON")
    parser.add_argument("--reprocess-from", metavar="EVIDENCE_DIR")
    args = parser.parse_args(argv)

    layout = None
    env_pairs = args.env
    reprocessed_from = None
    if args.reprocess_from:
        source = args.reprocess_from.rstrip("/")
        with open(os.path.join(source, "load.json"), encoding="utf-8") as f:
            old = json.load(f)
        args.raw = os.path.join(source, "raw.json.gz")
        args.profile = old["profile"]
        args.stage = old["stage"]
        args.db_issued_rows = old["db"]["issued_rows"]
        env_pairs = ["%s=%s" % (k, v) for k, v in old["env"].items()]
        layout = layout_from_report(old)
        args.stats = os.path.join(source, "stats.jsonl")
        args.summary = os.path.join(source, "summary.json")
        reprocessed_from = source
    elif None in (args.raw, args.profile, args.stage, args.db_issued_rows):
        parser.error("--raw, --profile, --stage and --db-issued-rows are required without --reprocess-from")

    if layout is None:
        with open(args.profiles, encoding="utf-8") as f:
            layout = json.load(f)
    if args.profile not in layout["profiles"]:
        parser.error("unknown profile: " + args.profile)
    if reprocessed_from and "layout" not in old:
        # No layout was stored with that run: profiles.json may have been retuned since, which would silently
        # apply the wrong step windows to the old data.
        recorded = [stage["target_rps"] for stage in old.get("stages", [])]
        current = [step["rps"] for step in layout["profiles"][args.profile]["steps"]]
        if recorded != current:
            parser.error("profiles.json no longer matches %s (stages %s, profile now %s); reprocess a run whose "
                         "load.json stores its layout" % (reprocessed_from, recorded, current))
    report = build_report(args.raw, layout, args.profile, args.stage, args.db_issued_rows,
                          _env(env_pairs), args.filtered_raw, args.stats, args.summary)
    if reprocessed_from:
        report["reprocessed_from"] = reprocessed_from
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
        f.write("\n")
    print("postprocess: profile=%s stage=%s limit_rps=%s valid=%s" % (
        report["profile"], report["stage"], report["limit_rps"], str(report["validity"]["valid"]).lower()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
