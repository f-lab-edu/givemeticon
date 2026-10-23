#!/usr/bin/env bash
# Self-test of the coupon-lab harness (verify.sh, the three hooks and the non-Docker parts of the load runner). Run from coupon-lab/.
# Prints OK/FAIL/SKIP per item; exits 1 if any item failed.
set -uo pipefail

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 2; }

cd "$(dirname "$0")/.."
HOOKS=../.claude/hooks
SECONDS=0
fails=0
evidence_dir=""
evidence_tmp=""
moved_unit=""
touch_file="settings.gradle"
touch_backup=""

ok() { echo "OK   $1"; }
fail() { echo "FAIL $1${2:+ - $2}"; fails=$((fails + 1)); }

# exit code of "$@", output kept in $out
run() { out=$("$@" 2>&1); rc=$?; }
# exit code of a hook fed JSON on stdin
hook() { out=$(printf '%s' "$2" | "$HOOKS/$1" 2>&1); rc=$?; }
# one field of a JSON file as text (booleans lower-case); empty when missing or unreadable
json_get() {
    python3 -I -c '
import json, sys
try:
    v = json.load(open(sys.argv[1])).get(sys.argv[2])
except Exception:
    v = None
print("" if v is None else ("true" if v is True else "false" if v is False else v))
' "$1" "$2"
}
# portable mktemp (GNU and BSD)
mk() { mktemp "${TMPDIR:-/tmp}/harness-smoke.XXXXXX"; }

# Removes exactly one directory: the evidence dir item 6 created. Nothing else under evidence/ is touched.
cleanup_evidence() {
    case "$evidence_dir" in
        *..*) ;;
        evidence/s0/consistency/?*) rm -rf -- "$evidence_dir" ;;
    esac
}
# Removes the temporary untracked file item 11 put under evidence/.
cleanup_evidence_tmp() {
    case "$evidence_tmp" in
        *..*) ;;
        evidence/.smoke-untracked-*) rm -f -- "$evidence_tmp" ;;
    esac
    evidence_tmp=""
}
restore_unit() {
    if [ -n "$moved_unit" ] && [ -f "$moved_unit" ]; then
        mv "$moved_unit" build/verify/unit.json
        moved_unit=""
    fi
}
restore_touched() {
    if [ -n "$touch_backup" ] && [ -f "$touch_backup" ]; then
        cp "$touch_backup" "$touch_file"
        rm -f "$touch_backup"
        touch_backup=""
    fi
}
trap 'restore_touched; restore_unit; cleanup_evidence_tmp; cleanup_evidence' EXIT

head_short=$(git rev-parse --short HEAD)

# Items 6 and 7 start a Testcontainers-backed bundle run; without a Docker daemon they are skipped.
skipped=0
if command -v docker >/dev/null && docker info >/dev/null 2>&1; then docker_ok=true; else docker_ok=false; fi
skip() { echo "SKIP $1 (no docker)"; skipped=$((skipped + 1)); }

# 1. compile contract
run scripts/verify.sh compile
last=$(tail -n 1 <<<"$out")
if [ "$rc" -eq 0 ] && grep -qE '^VERIFY compile PASS passed=[0-9]+ failed=[0-9]+ skipped=[0-9]+ duration=[0-9]+s sha=[0-9a-f]{7,} dirty=(true|false) report=[^[:space:]]+$' <<<"$last"; then
    ok "1 verify compile: exit 0, VERIFY line matches contract"
else
    fail "1 verify compile" "exit=$rc last line: $last"
fi

# 2. unit
run scripts/verify.sh unit
if [ "$rc" -eq 0 ] && [ -f build/verify/unit.json ] && [ "$(json_get build/verify/unit.json sha)" = "$head_short" ]; then
    ok "2 verify unit: exit 0, unit.json sha == HEAD ($head_short)"
else
    fail "2 verify unit" "exit=$rc"
fi

# The stop hook also requires a clean coupon-lab tree, so its items (3, 4) run right after
# item 2, before item 6 creates evidence. Commit before running this script.
# 3. require-verify-on-stop with and without a passing unit.json
hook require-verify-on-stop.sh '{"stop_hook_active":false}'
rc_verified=$rc
msg_verified=$out
# "touched" must not depend on what this branch contains, so force it with a temporary change
touch_backup=$(mk)
cp "$touch_file" "$touch_backup"
echo "# smoke" >>"$touch_file"
moved_unit=$(mk)
mv build/verify/unit.json "$moved_unit" 2>/dev/null
hook require-verify-on-stop.sh '{"stop_hook_active":false}'
rc_missing=$rc
restore_unit
restore_touched
if [ "$rc_verified" -eq 0 ] && [ "$rc_missing" -eq 2 ]; then
    ok "3 require-verify-on-stop: exit 0 with clean tree and verified unit.json, exit 2 without it, unit.json restored"
else
    fail "3 require-verify-on-stop" "verified exit=$rc_verified (want 0: $msg_verified), missing exit=$rc_missing (want 2)"
fi

# 4. require-verify-on-stop with a temporarily modified coupon-lab file
touch_backup=$(mk)
cp "$touch_file" "$touch_backup"
echo "# smoke" >>"$touch_file"
hook require-verify-on-stop.sh '{"stop_hook_active":false}'
rc_modified=$rc
msg_modified=$out
restore_touched
hook require-verify-on-stop.sh '{"stop_hook_active":false}'
rc_restored=$rc
if [ "$rc_modified" -eq 2 ] && [[ "$msg_modified" == *"uncommitted changes"* ]] && [ "$rc_restored" -eq 0 ]; then
    ok "4 require-verify-on-stop: exit 2 ($msg_modified) with $touch_file modified, exit 0 after restore"
else
    fail "4 require-verify-on-stop with modified file" "modified exit=$rc_modified ($msg_modified), restored exit=$rc_restored (want 0)"
fi

# 5. no matching test -> 6 (this run overwrites unit.json with a NO_TESTS report)
run scripts/verify.sh unit --tests 'NoSuch*'
if [ "$rc" -eq 6 ]; then ok "5 verify unit --tests 'NoSuch*': exit 6"; else fail "5 verify unit --tests 'NoSuch*'" "exit=$rc"; fi

# 6. bundle consistency s0 checks the bundle wiring, not the test outcome: exit 0 and 1 are both fine
if [ "$docker_ok" = true ]; then
    existing=$(ls -1 evidence/s0/consistency 2>/dev/null || true)
    run scripts/verify.sh bundle consistency --stage s0
    found=$(json_get build/verify/bundle-consistency.json evidence_dir)
    if [ -n "$found" ] && grep -qxF -- "${found##*/}" <<<"$existing"; then
        fail "6 verify bundle consistency --stage s0" "$found existed before this run; left untouched"
    else
        # a dir that did not exist before this run is ours, whatever the exit code, so item 10 can remove it
        [ ! -d "$found" ] || evidence_dir=$found
        if { [ "$rc" -eq 0 ] || [ "$rc" -eq 1 ]; } && [ -n "$found" ] && [ -f "$found/consistency.json" ] \
            && [ "$(json_get "$found/consistency.json" stage)" = s0 ]; then
            ok "6 verify bundle consistency --stage s0: exit $rc, $found/consistency.json has stage s0"
        else
            fail "6 verify bundle consistency --stage s0" "exit=$rc evidence_dir=$found"
        fi
    fi
else
    skip "6 verify bundle consistency --stage s0"
fi

# 7. bundle without tests -> 6
if [ "$docker_ok" = true ]; then
    run scripts/verify.sh bundle load
    if [ "$rc" -eq 6 ]; then ok "7 verify bundle load: exit 6"; else fail "7 verify bundle load" "exit=$rc"; fi
else
    skip "7 verify bundle load"
fi

# 8. protect-paths
hook protect-paths.sh '{"tool_input":{"file_path":"coupon-lab/evidence/README.md"}}'
rc_blocked=$rc
msg_blocked=$out
hook protect-paths.sh '{"tool_input":{"file_path":"coupon-lab/src/main/java/X.java"}}'
if [ "$rc_blocked" -eq 2 ] && [ "$rc" -eq 0 ]; then
    ok "8 protect-paths: evidence blocked (exit 2: $msg_blocked), src allowed (exit 0)"
else
    fail "8 protect-paths" "evidence exit=$rc_blocked, src exit=$rc"
fi

# 9. compile-after-edit ignores non-java files
hook compile-after-edit.sh '{"tool_input":{"file_path":"coupon-lab/build.gradle"}}'
if [ "$rc" -eq 0 ]; then ok "9 compile-after-edit: build.gradle ignored (exit 0)"; else fail "9 compile-after-edit" "exit=$rc"; fi

# 10. remove the one evidence directory item 6 created (only this script deletes evidence)
if [ "$docker_ok" != true ]; then
    ok "10 cleanup: nothing to remove (item 6 skipped)"
else
    cleanup_evidence
    if [ -n "$evidence_dir" ] && [ ! -e "$evidence_dir" ]; then
        ok "10 cleanup: removed $evidence_dir"
    else
        fail "10 cleanup" "evidence_dir='$evidence_dir' still present or never created"
    fi
fi
evidence_dir=""

# 11. an untracked file under evidence/ does not make verify.sh dirty nor count as "uncommitted changes",
#     but the stop hook still refuses to finish until it is committed. Needs the clean tree restored by item 10;
#     the unit run re-validates unit.json (item 5 left a NO_TESTS report) and leaves it valid.
evidence_tmp="evidence/.smoke-untracked-$$"
: >"$evidence_tmp"
run scripts/verify.sh compile
last=$(tail -n 1 <<<"$out")
run scripts/verify.sh unit
rc_unit=$rc
hook require-verify-on-stop.sh '{"stop_hook_active":false}'
rc_untracked=$rc
msg_untracked=$out
cleanup_evidence_tmp
hook require-verify-on-stop.sh '{"stop_hook_active":false}'
rc_clean=$rc
if [[ "$last" == *" dirty=false "* ]] && [ "$rc_unit" -eq 0 ] \
    && [ "$rc_untracked" -eq 2 ] && [[ "$msg_untracked" == *"commit evidence/"* ]] && [ "$rc_clean" -eq 0 ]; then
    ok "11 untracked evidence file: compile dirty=false, stop hook exit 2 ($msg_untracked), exit 0 after removal"
else
    fail "11 untracked evidence file" "compile line: $last; unit exit=$rc_unit; hook exit=$rc_untracked ($msg_untracked); after removal exit=$rc_clean (want 0)"
fi

# 12. a stage that does not exist on this branch stops before Gradle starts
run scripts/verify.sh bundle consistency --stage s9
if [ "$rc" -eq 2 ] && [[ "$out" == *"S9CouponIssueService not found on this branch"* ]]; then
    ok "12 verify bundle --stage s9: exit 2 (S9CouponIssueService not found on this branch)"
else
    fail "12 verify bundle --stage s9" "exit=$rc output: $out"
fi

# 13. verify.sh load: the idle-machine precondition (exit 2) and the INFRA_DOWN path (exit 3). Both run against
#     stand-ins for docker and k6 placed first on PATH, so no real container is started or inspected, no load is
#     generated and Docker is not needed (nor is an actually idle machine).
shim=$(mk)
rm -f "$shim"
mkdir "$shim"
printf '#!/bin/sh\necho "k6 v0.0.0 (smoke)"\n' >"$shim/k6"
cat >"$shim/docker" <<'SHIM'
#!/bin/sh
case "$1" in
    info) exit 0 ;;
    ps) printf '%s' "$SMOKE_PS"; exit 0 ;;
    *) exit 1 ;;
esac
SHIM
chmod +x "$shim/k6" "$shim/docker"
out=$(SMOKE_PS=$'legacy-redis\nlegacy-kafka\n' PATH="$shim:$PATH" scripts/verify.sh load --stage s0 --profile ramping 2>&1)
rc_busy=$?
msg_busy=$out
out=$(SMOKE_PS=$'coupon-lab-load-app\n' PATH="$shim:$PATH" scripts/verify.sh load --stage s0 --profile ramping 2>&1)
rc_infra=$?
msg_infra=$(tail -n 1 <<<"$out")
rm -rf "$shim"
if [ "$rc_busy" -eq 2 ] && [[ "$msg_busy" == *"legacy-redis"* ]] && [[ "$msg_busy" == *"stop other containers first"* ]] \
    && [ "$rc_infra" -eq 3 ] && [[ "$msg_infra" == "VERIFY load-ramping INFRA_DOWN "* ]]; then
    ok "13 verify load: exit 2 with other containers up, exit 3 INFRA_DOWN when compose fails (coupon-lab-load-* ignored)"
else
    fail "13 verify load" "busy exit=$rc_busy ($msg_busy), infra exit=$rc_infra ($msg_infra)"
fi

# 14. postprocess.py on synthetic k6 output
run env PYTHONDONTWRITEBYTECODE=1 python3 -I -m unittest discover -s scripts/load
if [ "$rc" -eq 0 ]; then ok "14 scripts/load unit tests"; else fail "14 scripts/load unit tests" "exit=$rc $(tail -n 3 <<<"$out")"; fi

# 15. verify.sh load rejects an unknown --k6 mode before touching docker
run scripts/verify.sh load --stage s0 --profile ramping --k6 nope
if [ "$rc" -eq 2 ] && [[ "$out" == *usage* ]]; then ok "15 verify load --k6 nope: exit 2 (usage)"; else fail "15 verify load --k6 nope" "exit=$rc"; fi

total=$((15 - skipped))
suffix=""
[ "$skipped" -eq 0 ] || suffix=" skipped=$skipped"
echo "harness-smoke: $((total - fails))/$total OK in ${SECONDS}s$suffix"
[ "$fails" -eq 0 ]
