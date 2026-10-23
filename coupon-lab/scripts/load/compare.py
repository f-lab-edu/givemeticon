#!/usr/bin/env python3
"""Compare saved test-B runs across repetitions and stages. Standard library only.

usage: python3 -I compare.py [--out FILE] [--p50-at RPS] <evidence dir | load.json | glob> [...]

Reads the load.json of each run (it must have sustained_limit_rps, i.e. be produced or reprocessed by the current
postprocess.py) and prints a markdown report, also written to --out:
  Runs            one row per run: sustained_limit_rps, max_achieved_rps, p50 at one target, stalls, run_order, ...
  Per stage       median and min-max of sustained_limit_rps, max_achieved_rps and the p50 at that target over the runs
                  of each stage. The p50 at a mid-range target (--p50-at, default 650) shows where queueing starts even
                  when both stages still count as ok there.
  Per target_rps  median p95 and achieved rps and the kinds seen at each target, per stage
Runs must share the profile and the step layout. Runs of one stage that differ in env (for example k6_mode) are
pooled but listed under the table, because evidence is only comparable across the same env block.
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


def load_runs(paths):
    runs = []
    for path in paths:
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
        missing = [k for k in ("sustained_limit_rps", "max_achieved_rps", "stalls") if k not in report]
        if missing:
            raise SystemExit("%s lacks %s; reprocess it with scripts/verify.sh reprocess first" % (path, ", ".join(missing)))
        runs.append({"path": path, "name": os.path.basename(os.path.dirname(path)), "report": report})
    layouts = set((r["report"]["profile"], tuple(s["target_rps"] for s in r["report"]["stages"])) for r in runs)
    if len(layouts) != 1:
        raise SystemExit("runs differ in profile or stage layout: %s" % sorted(layouts))
    return sorted(runs, key=lambda r: (r["report"]["stage"], r["name"]))


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
    keys = sorted(set(k for r in runs for k in r["report"]["env"]))
    notes = []
    for key in keys:
        values = [r["report"]["env"].get(key) for r in runs]
        if len(set(values)) > 1:
            notes.append("%s: %s" % (key, ", ".join(sorted(set("(missing)" if v is None else str(v) for v in values)))))
    return notes


def p50_at(report, target):
    for stage in report["stages"]:
        if stage["target_rps"] == target:
            return stage["p50_ms"]
    return None


def build_markdown(runs, p50_target=650):
    report0 = runs[0]["report"]
    stages = sorted(set(r["report"]["stage"] for r in runs))
    by_stage = dict((s, [r for r in runs if r["report"]["stage"] == s]) for s in stages)
    out = ["# Load test B comparison (profile %s)" % report0["profile"], ""]

    out += ["## Runs", "",
            "| stage | run | k6 | order | sustained_limit_rps | max_achieved_rps (at target) | p50@%d ms | stalls | first_violation | valid |" % p50_target,
            "|---|---|---|---|---|---|---|---|---|---|"]
    for r in runs:
        rep = r["report"]
        best = rep["max_achieved_rps"]
        out.append("| %s | %s | %s | %s | %s | %s (at %s) | %s | %d | %s | %s |" % (
            rep["stage"], r["name"], rep["env"].get("k6_mode", "-"), fmt((rep.get("run") or {}).get("run_order")),
            fmt(rep["sustained_limit_rps"]), fmt(best["achieved_rps"]), fmt(best["target_rps"]),
            fmt(p50_at(rep, p50_target), 2), len(rep["stalls"]),
            rep["limit_rps_first_violation"], "yes" if rep["validity"]["valid"] else "no"))

    out += ["", "## Per stage (median, min-max over runs)", "",
            "| stage | n | sustained_limit_rps | max_achieved_rps | p50@%d ms | stalls (total) |" % p50_target,
            "|---|---|---|---|---|---|"]
    for stage in stages:
        group = [r["report"] for r in by_stage[stage]]
        out.append("| %s | %d | %s | %s | %s | %d |" % (
            stage, len(group), median_range([g["sustained_limit_rps"] for g in group], 0),
            median_range([g["max_achieved_rps"]["achieved_rps"] for g in group]),
            median_range([p50_at(g, p50_target) for g in group], 2), sum(len(g["stalls"]) for g in group)))
    for stage in stages:
        notes = env_differences(by_stage[stage])
        if notes:
            out.append("")
            out.append("Note: the %s runs are pooled although their env differs - %s." % (stage, "; ".join(notes)))

    out += ["", "## Per target_rps (median over runs; kinds = ok/stall/saturated counts)", ""]
    out.append("| target_rps | " + " | ".join("%s p95 ms | %s achieved | %s kinds" % (s, s, s) for s in stages) + " |")
    out.append("|---|" + "---|---|---|" * len(stages))
    for index, stage0 in enumerate(report0["stages"]):
        cells = []
        for stage in stages:
            rows = [r["report"]["stages"][index] for r in by_stage[stage]]
            kinds = [row["kind"] for row in rows]
            cells += [fmt(statistics.median([row["p95_ms"] for row in rows if row["p95_ms"] is not None] or [None])),
                      fmt(statistics.median([row["achieved_rps"] for row in rows])),
                      "%d/%d/%d" % (kinds.count("ok"), kinds.count("stall"), kinds.count("saturated"))]
        out.append("| %d | %s |" % (stage0["target_rps"], " | ".join(cells)))
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
