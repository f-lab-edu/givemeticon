#!/usr/bin/env bash
# PreToolUse (Edit|Write): block edits to paths listed in .claude/protected-paths.txt.
set -euo pipefail

root=$(realpath "$(dirname "$0")/../..")
if ! command -v jq >/dev/null; then
    echo "Blocked: jq is required by protect-paths.sh and was not found" >&2
    exit 2
fi

path=$(jq -r '.tool_input.file_path // empty')
[ -n "$path" ] || exit 0
case "$path" in /*) ;; *) path="$root/$path" ;; esac
rel=$(realpath -m --relative-to="$root" "$path")
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
