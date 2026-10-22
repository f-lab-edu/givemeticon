#!/usr/bin/env bash
# Self-test of the coupon-lab harness (verify.sh and the three hooks). Run from coupon-lab/.
# Prints OK/FAIL per item; exits 1 if any item failed.
set -uo pipefail

cd "$(dirname "$0")/.."
HOOKS=../.claude/hooks
SECONDS=0
fails=0
evidence_dir=""
moved_unit=""
touch_file="settings.gradle"
touch_backup=""

ok() { echo "OK   $1"; }
fail() { echo "FAIL $1${2:+ - $2}"; fails=$((fails + 1)); }

# exit code of "$@", output kept in $out
run() { out=$("$@" 2>&1); rc=$?; }
# exit code of a hook fed JSON on stdin
hook() { out=$(printf '%s' "$2" | "$HOOKS/$1" 2>&1); rc=$?; }

# Only removes the directory item 4 created, and only if it is where verify.sh puts bundle evidence.
cleanup_evidence() {
    case "$evidence_dir" in
        evidence/s0/consistency/?*)
            rm -rf -- "$evidence_dir"
            rmdir evidence/s0/consistency evidence/s0 2>/dev/null || true
            ;;
    esac
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
trap 'restore_touched; restore_unit; cleanup_evidence' EXIT

head_short=$(git rev-parse --short HEAD)

# 1. compile contract
run scripts/verify.sh compile
last=$(tail -n 1 <<<"$out")
if [ "$rc" -eq 0 ] && grep -qP '^VERIFY compile PASS passed=\d+ failed=\d+ skipped=\d+ duration=\d+s sha=[0-9a-f]{7,} dirty=(true|false) report=\S+$' <<<"$last"; then
    ok "1 verify compile: exit 0, VERIFY line matches contract"
else
    fail "1 verify compile" "exit=$rc last line: $last"
fi

# 2. unit
run scripts/verify.sh unit
if [ "$rc" -eq 0 ] && [ -f build/verify/unit.json ] && [ "$(jq -r .sha build/verify/unit.json)" = "$head_short" ]; then
    ok "2 verify unit: exit 0, unit.json sha == HEAD ($head_short)"
else
    fail "2 verify unit" "exit=$rc"
fi

# The stop hook now also requires a clean coupon-lab tree, so its items (3, 4) run right after
# item 2, before items 6/10 create and remove placeholder evidence. Commit before running this script.
# 3. require-verify-on-stop with and without a passing unit.json
hook require-verify-on-stop.sh '{"stop_hook_active":false}'
rc_verified=$rc
msg_verified=$out
# "touched" must not depend on what this branch contains, so force it with a temporary change
touch_backup=$(mktemp)
cp "$touch_file" "$touch_backup"
echo "# smoke" >>"$touch_file"
moved_unit=$(mktemp)
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
touch_backup=$(mktemp)
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

# 6. bundle consistency s0 writes evidence
run scripts/verify.sh bundle consistency --stage s0
if [ "$rc" -eq 0 ] && [ -f build/verify/bundle-consistency.json ]; then
    evidence_dir=$(jq -r '.evidence_dir // empty' build/verify/bundle-consistency.json)
fi
if [ "$rc" -eq 0 ] && [ -n "$evidence_dir" ] && [ -f "$evidence_dir/consistency.json" ] \
    && [ "$(jq -r .stage "$evidence_dir/consistency.json")" = s0 ]; then
    ok "6 verify bundle consistency --stage s0: exit 0, $evidence_dir/consistency.json has stage s0"
else
    fail "6 verify bundle consistency --stage s0" "exit=$rc evidence_dir=$evidence_dir"
fi

# 7. bundle without tests -> 6
run scripts/verify.sh bundle load
if [ "$rc" -eq 6 ]; then ok "7 verify bundle load: exit 6"; else fail "7 verify bundle load" "exit=$rc"; fi

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

# 10. remove the placeholder evidence item 6 created (only this script deletes evidence)
cleanup_evidence
if [ -n "$evidence_dir" ] && [ ! -e "$evidence_dir" ]; then
    ok "10 cleanup: removed $evidence_dir"
else
    fail "10 cleanup" "evidence_dir='$evidence_dir' still present or never created"
fi
evidence_dir=""

echo "harness-smoke: $((10 - fails))/10 OK in ${SECONDS}s"
[ "$fails" -eq 0 ]
