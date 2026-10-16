#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
LOCAL="$ROOT/.agent-orchestrator/local"
VERSION=0.14.2
SHA256=9f989fb2d96ed443938c1536b048a5850018330f916caa8807d6cb587db17529
[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] || {
  echo 'This pinned installer supports macOS arm64 only; no automatic platform substitution.' >&2
  exit 1
}
mkdir -p "$LOCAL"
ARCHIVE="$LOCAL/agent-orchestrator-v$VERSION.zip"
if [ ! -f "$ARCHIVE" ]; then
  curl --fail --location --retry 2 \
    "https://github.com/OrchestratorInc/agent-orchestrator/releases/download/v$VERSION/agent-orchestrator-darwin-arm64.zip" \
    --output "$ARCHIVE"
fi
printf '%s  %s\n' "$SHA256" "$ARCHIVE" | shasum -a 256 -c -
# Always restore the pinned package, including when an older app directory exists.
# Stop the local daemon before reinstalling.
ditto -x -k "$ARCHIVE" "$LOCAL/app"
sh "$ROOT/scripts/agent-orchestrator/ao" doctor --json
