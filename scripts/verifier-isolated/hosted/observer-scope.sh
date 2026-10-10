#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux && $(uname -m) == x86_64 ]] || { echo 'hosted Linux x64 only' >&2; exit 2; }
repo=$(cd "$(dirname "$0")/../../.." && pwd)
# Only public routing metadata is inherited, never a password or credential value.
vars=GITHUB_ACTIONS,RUNNER_OS,GITHUB_RUN_ID,GITHUB_RUN_ATTEMPT,RUNNER_TEMP,GITHUB_WORKSPACE
unit="coupon-b-observers-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
description="coupon-calibration-owner-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
load=$(sudo -n systemctl show "$unit.scope" --property=LoadState --value)
[[ "$load" == not-found ]] || { echo 'existing observer scope refused' >&2; exit 2; }
finish_scope() {
  status=$?; trap - EXIT
  final_load=$(sudo -n systemctl show "$unit.scope" --property=LoadState --value 2>/dev/null) || final_load=unknown
  if [[ "$final_load" == not-found ]]; then exit "$status"; fi
  actual=$(sudo -n systemctl show "$unit.scope" --property=Description --value 2>/dev/null) || actual=''
  if [[ "$actual" == "$description" ]]; then
    sudo -n systemctl stop "$unit.scope" || { echo 'observer scope stop failed' >&2; [[ $status != 0 ]] || status=3; }
  elif [[ -n "$actual" ]]; then
    echo 'observer scope identity mismatch; stop refused' >&2
    [[ $status != 0 ]] || status=3
  fi
  exit "$status"
}
trap finish_scope EXIT
sudo -n --preserve-env="$vars" systemd-run --scope --unit="$unit" --description="$description" \
  --property=CPUQuota=25% --property=MemoryMax=512M --property=MemorySwapMax=0 \
  sudo -n --user="$(id -un)" --preserve-env="$vars" bash "$repo/scripts/verifier-isolated/hosted/run-calibration.sh"
