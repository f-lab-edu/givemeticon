#!/usr/bin/env bash
set -euo pipefail

# k6 원본 JSON에서 HTTP 200 표본만 사용해 성공 접수 지연을 재계산한다.
# 사용: bash scripts/coupon-admission/summarize-admission-loadtest.sh [runs-directory]
run_root=${1:-"$(cd "$(dirname "$0")/../.." && pwd)/기록/coupon-admission-loadtest/runs"}

printf 'run_id\tattempts\tdropped\tsuccess\ttimeouts\tsuccess_p50_ms\tsuccess_p95_ms\tsuccess_p99_ms\tsuccess_under_2s\n'
for run_dir in "$run_root"/admission-*; do
  [[ -f "$run_dir/k6-summary.json" && -f "$run_dir/k6.json" ]] || continue
  summary="$run_dir/k6-summary.json"
  values=$(mktemp)
  trap 'rm -f "$values"' EXIT
  jq -r 'select(.metric == "http_req_duration" and .data.tags.status == "200") | .data.value' "$run_dir/k6.json" | sort -n > "$values"
  quantiles=$(awk '{a[NR]=$1} END {if (NR == 0) exit 1; p50=int((NR-1)*0.50)+1; p95=int((NR-1)*0.95)+1; p99=int((NR-1)*0.99)+1; printf "%.3f\t%.3f\t%.3f", a[p50],a[p95],a[p99]}' "$values")
  IFS=$'\t' read -r success_p50 success_p95 success_p99 <<< "$quantiles"
  rm -f "$values"
  trap - EXIT
  jq -r --arg run_id "$(basename "$run_dir")" --arg success_p50 "$success_p50" --arg success_p95 "$success_p95" --arg success_p99 "$success_p99" '
    [ $run_id,
      .metrics.iterations.count,
      (.metrics.dropped_iterations.count // 0),
      .metrics.admission_success.passes,
      (.metrics.admission_timeouts.count // 0),
      $success_p50,
      $success_p95,
      $success_p99,
      .metrics.admission_success_under_2_seconds.passes
    ] | @tsv' "$summary"
done
