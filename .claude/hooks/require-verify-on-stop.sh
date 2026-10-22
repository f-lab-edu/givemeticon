#!/usr/bin/env bash
# Stop: if this task touched coupon-lab, require a passing unit run recorded for the current, committed HEAD.
# Untracked files under coupon-lab/evidence/ do not count as "uncommitted changes" (verify.sh writes them),
# but they must be committed before finishing.
set -euo pipefail

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 2; }
root=$(cd "$(dirname "$0")/../.." && pwd -P)

active=$(python3 -I -c '
import json, sys
print("true" if json.load(sys.stdin).get("stop_hook_active") is True else "false")
')
[ "$active" != true ] || exit 0

cd "$root"
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

status=$(git status --porcelain -- coupon-lab)
evidence_untracked=$(printf '%s\n' "$status" | grep '^?? coupon-lab/evidence/' || true)
code_changes=$(printf '%s\n' "$status" | grep -v -e '^?? coupon-lab/evidence/' -e '^$' || true)

touched=false
[ -z "$status" ] || touched=true
if git rev-parse --verify --quiet origin/develop >/dev/null; then
    # commits of this branch that are not on develop
    [ -z "$(git diff --name-only origin/develop...HEAD -- coupon-lab)" ] || touched=true
elif git log -1 --name-only --format= 2>/dev/null | grep -q '^coupon-lab/'; then
    touched=true
fi
[ "$touched" = true ] || exit 0

block() { echo "$1" >&2; exit 2; }

# one field of unit.json as text (booleans lower-case); empty when missing or unreadable
unit_field() {
    python3 -I -c '
import json, sys
try:
    v = json.load(open(sys.argv[1])).get(sys.argv[2])
except Exception:
    v = None
print("" if v is None else ("true" if v is True else "false" if v is False else v))
' "$report" "$1"
}

head=$(git rev-parse --short HEAD)
report=coupon-lab/build/verify/unit.json
[ -f "$report" ] || block "coupon-lab/build/verify/unit.json not found; run coupon-lab/scripts/verify.sh unit for HEAD $head before finishing"
[ -z "$code_changes" ] || block "coupon-lab has uncommitted changes; commit, then run coupon-lab/scripts/verify.sh unit"
[ "$(unit_field sha)" = "$head" ] || block "Run coupon-lab/scripts/verify.sh unit for HEAD $head before finishing"
code=$(unit_field exit_code)
[ "$code" = 0 ] || block "last unit run did not pass (exit_code=$code); fix, commit, then run coupon-lab/scripts/verify.sh unit"
[ "$(unit_field dirty)" = false ] || block "last unit run was on a dirty tree; run coupon-lab/scripts/verify.sh unit on the committed HEAD"
[ -z "$evidence_untracked" ] || block "commit evidence/ before finishing"
exit 0
