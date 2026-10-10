#!/usr/bin/env python3
"""Read Linux host capacity before any experiment container is created."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess


def assess(cpu_count, available_mib, disk_mib):
    # Both paths use these caps. Observers/build/OS have explicit reserve.
    caps = {"apps": {"count": 2, "cpu_each": 0.5, "memory_mib_each": 512},
            "mysql": {"cpu": 1, "memory_mib": 1024},
            "redis": {"count": 2, "cpu_each": 0.1, "memory_mib_each": 64},
            "generator": {"cpu": 1, "memory_mib": 1536},
            "observers": {"cpu_budget": 0.5, "memory_budget_mib": 512},
            "host_reserve": {"cpu_budget": 0.25, "memory_budget_mib": 2048}}
    required_cpu = 3.95
    required_mib = 6272
    return {"caps": caps, "required_cpu": required_cpu, "required_memory_mib": required_mib,
            "available_cpu": cpu_count, "available_memory_mib": available_mib,
            "available_disk_mib": disk_mib,
            "pass": cpu_count >= required_cpu and available_mib >= required_mib and disk_mib >= 6144}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        raise SystemExit("requires Linux x64 hosted runner; local capability execution refused")
    mem = {}
    for line in Path("/proc/meminfo").read_text().splitlines():
        fields = line.split()
        mem[fields[0].rstrip(":")] = int(fields[1])
    result = assess(len(os.sched_getaffinity(0)), mem["MemAvailable"] // 1024,
                    shutil.disk_usage(".").free // 1024**2)
    result.update({"platform": platform.platform(), "memory_total_mib": mem["MemTotal"] // 1024,
                   "sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                   "image_os": os.environ.get("ImageOS", "unknown"),
                   "image_version": os.environ.get("ImageVersion", "unknown"),
                   "scope": "capacity check; observers/reserve budgeted but not isolated; not safety or workload PASS"})
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    raise SystemExit(0 if result["pass"] else 3)

if __name__ == "__main__":
    main()
