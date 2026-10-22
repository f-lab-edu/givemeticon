#!/usr/bin/env bash
# Stop: if this task touched coupon-lab, require a passing unit run recorded for the current, committed HEAD.
set -euo pipefail

root=$(realpath "$(dirname "$0")/../..")
[ "$(jq -r '.stop_hook_active // false')" != true ] || exit 0

cd "$root"
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

status=$(git status --porcelain -- coupon-lab)
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

head=$(git rev-parse --short HEAD)
report=coupon-lab/build/verify/unit.json
[ -f "$report" ] || block "coupon-lab/build/verify/unit.json not found; run coupon-lab/scripts/verify.sh unit for HEAD $head before finishing"
[ -z "$status" ] || block "coupon-lab has uncommitted changes; commit, then run coupon-lab/scripts/verify.sh unit"
[ "$(jq -r '.sha' "$report")" = "$head" ] || block "Run coupon-lab/scripts/verify.sh unit for HEAD $head before finishing"
code=$(jq -r '.exit_code' "$report")
[ "$code" = 0 ] || block "last unit run did not pass (exit_code=$code); fix, commit, then run coupon-lab/scripts/verify.sh unit"
[ "$(jq -r '.dirty' "$report")" = false ] || block "last unit run was on a dirty tree; run coupon-lab/scripts/verify.sh unit on the committed HEAD"
exit 0
