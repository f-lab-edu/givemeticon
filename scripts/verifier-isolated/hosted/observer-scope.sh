#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux && $(uname -m) == x86_64 ]] || { echo 'hosted Linux x64 only' >&2; exit 2; }
repo=$(cd "$(dirname "$0")/../../.." && pwd)
# Only public routing metadata is inherited, never a password or credential value.
vars=GITHUB_ACTIONS,RUNNER_OS,GITHUB_RUN_ID,GITHUB_RUN_ATTEMPT,RUNNER_TEMP,GITHUB_WORKSPACE
unit="coupon-b-observers-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
exec sudo -n --preserve-env="$vars" systemd-run --scope --unit="$unit" \
  --property=CPUQuota=25% --property=MemoryMax=512M --property=MemorySwapMax=0 \
  sudo -n --user="$(id -un)" --preserve-env="$vars" bash "$repo/scripts/verifier-isolated/hosted/run-calibration.sh"
