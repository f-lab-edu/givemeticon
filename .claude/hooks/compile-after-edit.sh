#!/usr/bin/env bash
# PostToolUse (Edit|Write): compile coupon-lab after a .java file under it changed.
set -euo pipefail

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 2; }
root=$(cd "$(dirname "$0")/../.." && pwd -P)

rel=$(python3 -I -c '
import json, os, sys
path = (json.load(sys.stdin).get("tool_input") or {}).get("file_path") or ""
if path:
    root = os.path.realpath(sys.argv[1])
    print(os.path.relpath(os.path.realpath(os.path.join(root, path)), root))
' "$root")
case "$rel" in
    coupon-lab/*.java) ;;
    *) exit 0 ;;
esac

if out=$(cd "$root/coupon-lab" && ./gradlew compileJava compileTestJava -q 2>&1); then
    exit 0
fi
errors=$(grep 'error:' <<<"$out" || true)
if [ -n "$errors" ]; then
    echo "$errors" >&2
else
    echo "compile failed without 'error:' lines; run coupon-lab/scripts/verify.sh compile" >&2
fi
exit 2
