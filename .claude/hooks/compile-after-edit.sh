#!/usr/bin/env bash
# PostToolUse (Edit|Write): compile coupon-lab after a .java file under it changed.
set -euo pipefail

root=$(realpath "$(dirname "$0")/../..")
path=$(jq -r '.tool_input.file_path // empty')
[ -n "$path" ] || exit 0
case "$path" in /*) ;; *) path="$root/$path" ;; esac
rel=$(realpath -m --relative-to="$root" "$path")
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
