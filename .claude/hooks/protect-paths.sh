#!/usr/bin/env bash
# PreToolUse (Edit|Write): block edits to paths listed in .claude/protected-paths.txt.
set -euo pipefail

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 2; }
root=$(cd "$(dirname "$0")/../.." && pwd -P)

# tool_input.file_path relative to the repo root; empty when absent
rel=$(python3 -I -c '
import json, os, sys
path = (json.load(sys.stdin).get("tool_input") or {}).get("file_path") or ""
if path:
    root = os.path.realpath(sys.argv[1])
    print(os.path.relpath(os.path.realpath(os.path.join(root, path)), root))
' "$root")
[ -n "$rel" ] || exit 0
case "$rel" in ..|../*) exit 0 ;; esac

list="$root/.claude/protected-paths.txt"
[ -f "$list" ] || exit 0
while IFS= read -r pattern || [ -n "$pattern" ]; do
    case "$pattern" in ''|'#'*) continue ;; esac
    case "$rel" in
        "$pattern"*)
            echo "Blocked: $rel is protected ($pattern)" >&2
            exit 2
            ;;
    esac
done <"$list"
exit 0
