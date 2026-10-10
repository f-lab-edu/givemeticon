#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux ]] || exit 2
repo=$(cd "$(dirname "$0")/../../.." && pwd); cd "$repo"
export VERIFIER_OWNER_ID="issue177-b-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
export VERIFIER_PREFIX="v177b-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
pw="${RUNNER_TEMP:?}/issue177-b-password"
source_dir="$repo/scripts/verifier-isolated/results/calibration"
mkdir -p "$source_dir"
status=0
# The common runner checks all existing labels before any mutation, including partial up.
for action in down remove; do
  rc=0; bash scripts/verifier-isolated/capped/run-capped-v1.sh "$action" > "$source_dir/cleanup-$action.log" 2>&1 || rc=$?
  printf '%s\n' "$rc" > "$source_dir/cleanup-$action-exit.txt"
  [[ $rc == 0 ]] || status=3
done
python3 scripts/verifier-isolated/hosted/collect-evidence.py "$source_dir" "$repo/build/calibration-evidence" "$pw" || status=3
rm -f "$pw"
exit "$status"
