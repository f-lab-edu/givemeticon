#!/usr/bin/env bash
# Fails when any *.json under the evidence directory is not valid JSON. Read-only.
#   scripts/check-evidence.sh [evidence-dir]      (default: the evidence/ next to scripts/)
# Prints "check-evidence: N json files, M invalid"; every invalid file is listed on stderr with the parser's message
# (line and column). Exit codes: 0 all valid, 1 at least one invalid file, 2 usage or setup error.
# Only *.json is parsed: raw.json.gz, stats.jsonl and gc.log are not JSON documents.
set -euo pipefail

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 2; }
[ $# -le 1 ] || { echo "usage: scripts/check-evidence.sh [evidence-dir]" >&2; exit 2; }
dir=${1:-"$(cd "$(dirname "$0")/.." && pwd -P)/evidence"}
[ -d "$dir" ] || { echo "not a directory: $dir" >&2; exit 2; }

python3 -I - "$dir" <<'PY'
import json
import os
import sys

root = sys.argv[1]
checked = 0
invalid = []
for folder, subdirs, files in os.walk(root):
    subdirs.sort()
    for name in sorted(files):
        if not name.endswith(".json"):
            continue
        path = os.path.join(folder, name)
        checked += 1
        try:
            with open(path, encoding="utf-8") as f:
                json.load(f)
        except (ValueError, OSError) as error:  # JSONDecodeError and UnicodeDecodeError are ValueErrors
            invalid.append((path, error))
for path, error in invalid:
    print("INVALID %s: %s" % (path, error), file=sys.stderr)
print("check-evidence: %d json files, %d invalid" % (checked, len(invalid)))
sys.exit(1 if invalid else 0)
PY
