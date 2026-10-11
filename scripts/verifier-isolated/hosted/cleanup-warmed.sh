#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux ]] || exit 2
repo=$(cd "$(dirname "$0")/../../.." && pwd); cd "$repo"
export VERIFIER_OWNER_ID="issue177-w-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
export VERIFIER_PREFIX="v177w-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
pw="${RUNNER_TEMP:?}/issue177-w-password"
source_dir="$repo/scripts/verifier-isolated/results/warmed"
mkdir -p "$source_dir"
status=0
# stock sources V1 owned_names/down/remove: both share owner, network and five containers.
# Regression test pins this equality; new stock-specific resources must extend cleanup.
# The common runner checks all existing labels before any mutation, including partial up.
for action in down remove; do
  rc=0; bash scripts/verifier-isolated/capped/run-capped-v1.sh "$action" > "$source_dir/cleanup-$action.log" 2>&1 || rc=$?
  printf '%s\n' "$rc" > "$source_dir/cleanup-$action-exit.txt"
  [[ $rc == 0 ]] || status=3
done
collection_rc=0
python3 scripts/verifier-isolated/hosted/collect-evidence.py "$source_dir" "$repo/build/warmed-evidence" "$pw" || collection_rc=$?
[[ $collection_rc == 0 ]] || status=3
# Safe metadata is independent of raw collection; initial capability/test evidence
# already lives in this destination and does not need a missing secret to be copied.
python3 - "$repo/build/warmed-evidence/collection-status.json" "$collection_rc" <<'PY_STATUS'
import json,sys
from pathlib import Path
p=Path(sys.argv[1]);p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(json.dumps({'raw_collection':'COLLECTED_REDACTED' if sys.argv[2]=='0' else 'REFUSED','exit_code':int(sys.argv[2]),'note':'missing/empty redaction input forbids raw copy; secret-free capability/test evidence is preserved separately'},indent=2)+'\n')
PY_STATUS
# Keep redaction input through EXIT-trap and always-rescue collections.
# The workflow removes it in the final always credential-cleanup step.
exit "$status"
