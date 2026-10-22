#!/usr/bin/env bash
# Stop: if this task touched coupon-lab, require a passing unit run recorded for the current HEAD.
set -euo pipefail

root=$(realpath "$(dirname "$0")/../..")
[ "$(jq -r '.stop_hook_active // false')" != true ] || exit 0

cd "$root"
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

touched=false
[ -z "$(git status --porcelain -- coupon-lab)" ] || touched=true
if git log -1 --name-only --format= 2>/dev/null | grep -q '^coupon-lab/'; then touched=true; fi
[ "$touched" = true ] || exit 0

head=$(git rev-parse --short HEAD)
report=coupon-lab/build/verify/unit.json
if [ -f "$report" ] \
    && [ "$(jq -r '.sha' "$report")" = "$head" ] \
    && [ "$(jq -r '.exit_code' "$report")" = 0 ]; then
    exit 0
fi
echo "Run coupon-lab/scripts/verify.sh unit for HEAD $head before finishing" >&2
exit 2
