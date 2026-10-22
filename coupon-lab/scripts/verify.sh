#!/usr/bin/env bash
# Single entry point for coupon-lab verification. Run from coupon-lab/.
#   verify.sh compile
#   verify.sh unit [--tests <pattern>]
#   verify.sh bundle <consistency|load|bottleneck|recovery|fairness> [--stage sN]
# Last stdout line:
#   VERIFY <tier> <PASS|FAIL|BUILD_ERROR|TIMEOUT|NO_TESTS> passed=N failed=N skipped=N duration=Ns sha=<short> dirty=<bool> report=<path>
# Exit codes: 0 PASS, 1 FAIL, 2 usage/config error, 4 TIMEOUT, 5 BUILD_ERROR, 6 NO_TESTS.
set -euo pipefail

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 2; }

BUNDLES="consistency load bottleneck recovery fairness"

usage() {
    echo "usage: scripts/verify.sh compile | unit [--tests <pattern>] | bundle <$(echo "$BUNDLES" | tr ' ' '|')> [--stage sN]" >&2
    exit 2
}

[ $# -ge 1 ] || usage
if [ ! -x ./gradlew ] || [ ! -f settings.gradle ] || ! grep -q "rootProject.name = 'coupon-lab'" settings.gradle; then
    echo "verify.sh must run from the coupon-lab/ directory (its own ./gradlew not found)" >&2
    exit 2
fi

mode=$1
shift
tests_pattern=""
stage=""
bundle=""
case "$mode" in
    compile)
        [ $# -eq 0 ] || usage
        tier=compile
        ;;
    unit)
        tier=unit
        if [ $# -gt 0 ]; then
            [ $# -eq 2 ] && [ "$1" = "--tests" ] || usage
            tests_pattern=$2
        fi
        ;;
    bundle)
        [ $# -ge 1 ] || usage
        bundle=$1
        shift
        case " $BUNDLES " in *" $bundle "*) ;; *) usage ;; esac
        stage=s0
        if [ $# -gt 0 ]; then
            [ $# -eq 2 ] && [ "$1" = "--stage" ] || usage
            stage=$2
        fi
        [[ "$stage" =~ ^s[0-9]+$ ]] || { echo "invalid stage: $stage (expected sN)" >&2; exit 2; }
        tier="bundle-$bundle"
        ;;
    *) usage ;;
esac

sha=$(git rev-parse --short HEAD 2>/dev/null) || { echo "not a git repository" >&2; exit 2; }
# Untracked files under evidence/ are this script's own output; they must not make later runs dirty.
if [ -n "$(git status --porcelain -- . 2>/dev/null | grep -v '^?? coupon-lab/evidence/' || true)" ]; then dirty=true; else dirty=false; fi

mkdir -p build/verify
log="build/verify/$tier.log"
report="build/verify/$tier.json"
started_epoch=$(date +%s)
started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
stamp=$(date -u +%Y%m%d-%H%M%S)
echo "verify $tier: sha=$sha dirty=$dirty started=$started_at${stage:+ stage=$stage}"

# Remove artifacts of earlier runs so a stale result can never be mistaken for this run.
case "$mode" in
    compile) results_dir="" ;;
    unit) results_dir="build/test-results/test" ;;
    bundle) results_dir="build/test-results/bundleTest" ;;
esac
[ -z "$results_dir" ] || rm -rf "$results_dir"
[ -z "$bundle" ] || rm -f "build/evidence/$bundle.json"

case "$mode" in
    compile) cmd=(./gradlew compileJava compileTestJava -q --console=plain) ;;
    unit)
        cmd=(./gradlew test --console=plain)
        [ -z "$tests_pattern" ] || cmd+=(--tests "$tests_pattern")
        ;;
    bundle) cmd=(./gradlew bundleTest "-Pbundle=$bundle" "-Pstage=$stage" --console=plain) ;;
esac

set +e
"${cmd[@]}" >"$log" 2>&1
gradle_exit=$?
set -e

ended_epoch=$(date +%s)
ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
duration=$((ended_epoch - started_epoch))

evidence_dir=""
if [ "$mode" = bundle ]; then
    dest="evidence/$stage/$bundle/$stamp-$sha"
    [ "$dirty" = false ] || dest="$dest-dirty"
    shopt -s nullglob
    xmls=("$results_dir"/TEST-*.xml)
    shopt -u nullglob
    if [ -f "build/evidence/$bundle.json" ] || [ ${#xmls[@]} -gt 0 ]; then
        mkdir -p "$dest"
        [ ! -f "build/evidence/$bundle.json" ] || cp "build/evidence/$bundle.json" "$dest/"
        [ ${#xmls[@]} -eq 0 ] || cp "${xmls[@]}" "$dest/"
        evidence_dir=$dest
    fi
    if [ ${#xmls[@]} -gt 0 ] && [ ! -f "build/evidence/$bundle.json" ]; then
        echo "WARN: build/evidence/$bundle.json was not written by the tests"
    fi
fi

# Parse JUnit XML, classify, write the JSON report. Prints "STATUS p f s" then failure lines.
parsed=$(python3 -I - "$mode" "$tier" "$stage" "$sha" "$dirty" "$started_at" "$ended_at" "$duration" \
    "$gradle_exit" "$log" "$results_dir" "$report" "$evidence_dir" <<'PY'
import glob, json, sys
import xml.etree.ElementTree as ET

(mode, tier, stage, sha, dirty, started_at, ended_at, duration,
 gradle_exit, log, results_dir, report, evidence_dir) = sys.argv[1:14]
gradle_exit = int(gradle_exit)

passed = failed = skipped = 0
failures = []
if results_dir:
    for path in sorted(glob.glob(results_dir + "/TEST-*.xml")):
        for case in ET.parse(path).getroot().iter("testcase"):
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is not None:
                failed += 1
                text = bad.get("message") or (bad.text or "")
                lines = [l.strip() for l in text.splitlines() if l.strip()]
                failures.append({"class": case.get("classname"), "method": case.get("name"),
                                 "message": (lines[0] if lines else "")[:200]})
            elif case.find("skipped") is not None:
                skipped += 1
            else:
                passed += 1

with open(log, errors="replace") as f:
    log_text = f.read()

if "Timeout has been exceeded" in log_text:
    status = "TIMEOUT"
elif mode == "compile":
    status = "PASS" if gradle_exit == 0 else "BUILD_ERROR"
elif "No tests found for given includes" in log_text:
    status = "NO_TESTS"
elif gradle_exit != 0 and failed == 0:
    status = "BUILD_ERROR"
elif failed > 0:
    status = "FAIL"
elif passed + skipped == 0:
    status = "NO_TESTS"
else:
    status = "PASS"

exit_code = {"PASS": 0, "FAIL": 1, "TIMEOUT": 4, "BUILD_ERROR": 5, "NO_TESTS": 6}[status]
with open(report, "w") as f:
    json.dump({
        "tier": tier, "stage": stage or None, "sha": sha, "dirty": dirty == "true",
        "status": status, "passed": passed, "failed": failed, "skipped": skipped,
        "failures": failures, "started_at": started_at, "ended_at": ended_at,
        "duration_s": int(duration), "exit_code": exit_code, "gradle_exit_code": gradle_exit,
        "evidence_dir": evidence_dir or None,
    }, f, indent=2)
    f.write("\n")

print(status, passed, failed, skipped)
for item in failures:
    print("FAILED %s.%s: %s" % (item["class"], item["method"], item["message"]))
PY
)

read -r status passed failed skipped <<<"$(head -n 1 <<<"$parsed")"
tail -n +2 <<<"$parsed"

if [ "$mode" = compile ] && [ "$status" = BUILD_ERROR ]; then
    grep 'error:' "$log" || echo "(no 'error:' lines; see $log)"
fi

case "$status" in
    PASS) code=0 ;;
    FAIL) code=1 ;;
    TIMEOUT) code=4 ;;
    BUILD_ERROR) code=5 ;;
    NO_TESTS) code=6 ;;
esac

echo "VERIFY $tier $status passed=$passed failed=$failed skipped=$skipped duration=${duration}s sha=$sha dirty=$dirty report=$report"
exit "$code"
