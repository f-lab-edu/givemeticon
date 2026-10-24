#!/usr/bin/env python3
"""Compare saved test-B/C runs across repetitions and variants. Standard library only.

usage: python3 -I compare.py [--out FILE] [--p50-at RPS] <evidence dir | load.json | glob> [...]

Reads the load.json of each run (it must have sustained_limit_rps, i.e. be produced or reprocessed by the current
postprocess.py) and prints a markdown report, also written to --out. Runs are grouped by
(stage, profile, rows, flush_log, pool); a run that predates the flush_log variable counts as flush_log 1 (the default).
  Runs            one row per run: sustained_limit_rps, max_achieved_rps, p50 at --p50-at (default 650), stalls, run_order, ...
  Row-lock        per run: row_lock_wait_ratio, row_lock_time_per_request_ms and fsyncs_per_request at the reference target
                  (--p50-at) and at the run's highest target (test C: where p50 rises, do requests wait on a row lock?)
  Groups          per group, median and min-max over its runs of sustained_limit_rps, max_achieved_rps, and - at the highest
                  target that every group has - p50 and row_lock_wait_ratio. Groups with different profiles are compared at
                  that common target only.
  Per target_rps  one table per profile layout: median p95 and achieved rps and the kinds seen at each target, per group.
Runs of one group must share the step layout. Runs of one group that differ in env (for example k6_mode) are pooled
but listed under the groups table, because evidence is only comparable across the same env block.
"""
import argparse
import glob
import json
import os
import statistics
import sys


def find_reports(patterns):
    paths = []
    for pattern in patterns:
        matches = sorted(glob.glob(pattern)) if any(c in pattern for c in "*?[") else [pattern]
        for match in matches:
            path = os.path.join(match, "load.json") if os.path.isdir(match) else match
            if not os.path.isfile(path):
                raise SystemExit("no load.json at " + match)
            paths.append(path)
    if not paths:
        raise SystemExit("no runs given")
    return paths


def group_key(report):
    env = report.get("env") or {}
    return (report["stage"], report["profile"], report.get("rows", 1),
            env.get("innodb_flush_log_at_trx_commit", 1), env.get("hikari_pool_size"))


def label(key):
    stage, profile, rows, flush, pool = key
    return "%s %s rows=%s flush=%s pool=%s" % (stage, profile, rows, flush, "-" if pool is None else pool)


def targets(report):
    return tuple(s["target_rps"] for s in report["stages"])


def load_runs(paths):
    runs = []
    for path in paths:
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
        missing = [k for k in ("sustained_limit_rps", "max_achieved_rps", "stalls") if k not in report]
        if missing:
            raise SystemExit("%s lacks %s; reprocess it with scripts/verify.sh reprocess first" % (path, ", ".join(missing)))
        runs.append({"path": path, "name": os.path.basename(os.path.dirname(path)), "report": report,
                     "key": group_key(report)})
    layouts = {}
    for run in runs:
        layouts.setdefault(run["key"], set()).add(targets(run["report"]))
    for key, found in layouts.items():
        if len(found) != 1:
            raise SystemExit("runs of %s differ in step layout: %s" % (label(key), sorted(found)))
    return sorted(runs, key=lambda r: (r["key"][0], r["key"][1], r["key"][2], r["key"][3],
                                       -1 if r["key"][4] is None else r["key"][4], r["name"]))


def fmt(value, digits=1):
    if value is None:
        return "-"
    if isinstance(value, float) and value == int(value):
        value = int(value)
    return str(value) if isinstance(value, int) else ("%." + str(digits) + "f") % value


def median_range(values, digits=1):
    values = [v for v in values if v is not None]
    if not values:
        return "-"
    return "%s (%s-%s)" % (fmt(statistics.median(values), digits), fmt(min(values), digits), fmt(max(values), digits))


def env_differences(runs):
    keys = sorted(set(k for r in runs for k in r["report"].get("env", {})))
    notes = []
    for key in keys:
        values = [r["report"].get("env", {}).get(key) for r in runs]
        if len(set(values)) > 1:
            notes.append("%s: %s" % (key, ", ".join(sorted(set("(missing)" if v is None else str(v) for v in values)))))
    return notes


def stage_value(report, target, key):
    for stage in report["stages"]:
        if stage["target_rps"] == target:
            return stage.get(key)
    return None


def highest_common_target(groups):
    """The highest target present in the layout of every group, or None."""
    common = None
    for runs in groups.values():
        found = set(targets(runs[0]["report"]))
        common = found if common is None else common & found
    return max(common) if common else None


def build_markdown(runs, p50_target=650):
    groups = {}
    for run in runs:
        groups.setdefault(run["key"], []).append(run)
    common = highest_common_target(groups)
    out = ["# Load test comparison (%d runs, %d groups)" % (len(runs), len(groups)), ""]

    out += ["## Runs", "",
            "| group | run | k6 | order | sustained_limit_rps | max_achieved_rps (at target) | p50@%d ms | stalls | first_violation | valid |" % p50_target,
            "|---|---|---|---|---|---|---|---|---|---|"]
    for r in runs:
        rep = r["report"]
        best = rep["max_achieved_rps"]
        out.append("| %s | %s | %s | %s | %s | %s (at %s) | %s | %d | %s | %s |" % (
            label(r["key"]), r["name"], rep["env"].get("k6_mode", "-"), fmt((rep.get("run") or {}).get("run_order")),
            fmt(rep["sustained_limit_rps"]), fmt(best["achieved_rps"]), fmt(best["target_rps"]),
            fmt(stage_value(rep, p50_target, "p50_ms"), 2), len(rep["stalls"]),
            rep["limit_rps_first_violation"], "yes" if rep["validity"]["valid"] else "no"))

    columns = ("row_lock_wait_ratio", "row_lock_time_per_request_ms", "fsyncs_per_request")
    out += ["", "## Row-lock waits and fsyncs per request (at the reference target and at each run's highest target)", "",
            "| group | run | ref rps | wait_ratio | lock ms/req | fsyncs/req | top rps | wait_ratio | lock ms/req | fsyncs/req |",
            "|---|---|---|---|---|---|---|---|---|---|"]
    for r in runs:
        rep = r["report"]
        top = max(targets(rep))
        cells = [fmt(stage_value(rep, t, key), 3) for t in (p50_target, top) for key in columns]
        out.append("| %s | %s | %d | %s | %s | %s | %d | %s | %s | %s |" % (
            label(r["key"]), r["name"], p50_target, cells[0], cells[1], cells[2], top, cells[3], cells[4], cells[5]))

    at = "-" if common is None else str(common)
    out += ["", "## Groups (median, min-max over runs; p50 and row-lock at the highest target all groups share: %s rps)" % at, "",
            "| group | n | sustained_limit_rps | max_achieved_rps | p50@%s ms | wait_ratio@%s | stalls (total) |" % (at, at),
            "|---|---|---|---|---|---|---|"]
    for key, members in groups.items():
        reports = [m["report"] for m in members]
        out.append("| %s | %d | %s | %s | %s | %s | %d |" % (
            label(key), len(members), median_range([g["sustained_limit_rps"] for g in reports], 0),
            median_range([g["max_achieved_rps"]["achieved_rps"] for g in reports]),
            median_range([stage_value(g, common, "p50_ms") for g in reports], 2),
            median_range([stage_value(g, common, "row_lock_wait_ratio") for g in reports], 3),
            sum(len(g["stalls"]) for g in reports)))
    for key, members in groups.items():
        notes = env_differences(members)
        if notes:
            out.append("")
            out.append("Note: the runs of %s are pooled although their env differs - %s." % (label(key), "; ".join(notes)))

    layouts = {}
    for key in groups:
        layouts.setdefault(targets(groups[key][0]["report"]), []).append(key)
    for layout, keys in sorted(layouts.items()):
        out += ["", "## Per target_rps, steps %s (median over runs; kinds = ok/stall/saturated counts)" % "/".join(map(str, layout)), ""]
        out.append("| target_rps | " + " | ".join("%s p95 ms | achieved | kinds" % label(k) for k in keys) + " |")
        out.append("|---|" + "---|---|---|" * len(keys))
        for index, target in enumerate(layout):
            cells = []
            for key in keys:
                rows_ = [m["report"]["stages"][index] for m in groups[key]]
                kinds = [row["kind"] for row in rows_]
                p95s = [row["p95_ms"] for row in rows_ if row["p95_ms"] is not None]
                cells += [fmt(statistics.median(p95s)) if p95s else "-",
                          fmt(statistics.median([row["achieved_rps"] for row in rows_])),
                          "%d/%d/%d" % (kinds.count("ok"), kinds.count("stall"), kinds.count("saturated"))]
            out.append("| %d | %s |" % (target, " | ".join(cells)))
    return "\n".join(out) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("runs", nargs="+", metavar="EVIDENCE_DIR_OR_GLOB")
    parser.add_argument("--out")
    parser.add_argument("--p50-at", type=int, default=650, metavar="RPS")
    args = parser.parse_args(argv)
    text = build_markdown(load_runs(find_reports(args.runs)), args.p50_at)
    sys.stdout.write(text)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
