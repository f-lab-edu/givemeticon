#!/usr/bin/env bash
set -euo pipefail
# A caller-generated owner token must be retained for this experiment's lifetime.
prefix=${VERIFIER_PREFIX:-verifier-iso}
mysql_port=${VERIFIER_MYSQL_PORT:-3307}
: "${VERIFIER_OWNER_ID:?set a unique VERIFIER_OWNER_ID and retain it for stop/remove}"
[[ "$prefix" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || { echo "invalid prefix" >&2; exit 2; }
containers=("$prefix-mysql" "$prefix-redis-mail" "$prefix-redis-coupon")
owner_label=xyz.buzz.verifier.owner
managed_label=xyz.buzz.verifier.managed
created=()
cleanup_failed_up() {
  status=$?
  trap - EXIT
  if [[ "$status" != 0 ]]; then
    for id in ${created[@]+"${created[@]}"}; do
      actual=$(docker inspect --format '{{ index .Config.Labels "xyz.buzz.verifier.owner" }}' "$id") || { echo "cleanup inspect failed: $id" >&2; continue; }
      managed=$(docker inspect --format '{{ index .Config.Labels "xyz.buzz.verifier.managed" }}' "$id") || { echo "cleanup inspect failed: $id" >&2; continue; }
      if [[ "$actual" == "$VERIFIER_OWNER_ID" && "$managed" == isolated-harness ]]; then
        docker stop "$id" >/dev/null || echo "cleanup stop failed: $id" >&2
      else
        echo "cleanup ownership mismatch: $id" >&2
      fi
    done
  fi
  exit "$status"
}
verify_owned() {
  # Check every target before performing any mutation.
  for name in "${containers[@]}"; do
    actual=$(docker inspect --format '{{ index .Config.Labels "xyz.buzz.verifier.owner" }}' "$name")
    managed=$(docker inspect --format '{{ index .Config.Labels "xyz.buzz.verifier.managed" }}' "$name")
    [[ "$actual" == "$VERIFIER_OWNER_ID" && "$managed" == "isolated-harness" ]] ||
      { echo "ownership mismatch: $name" >&2; exit 2; }
  done
}
case "${1:-}" in
  up)
    : "${VERIFIER_MYSQL_PASSWORD:?set VERIFIER_MYSQL_PASSWORD}"
    for name in "${containers[@]}"; do
      if docker inspect "$name" >/dev/null 2>&1; then
        echo "container already exists: $name" >&2; exit 2
      fi
    done
    for p in "$mysql_port" 16379 16380; do
      ! lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 || { echo "port $p in use" >&2; exit 2; }
    done
    trap cleanup_failed_up EXIT
    # Docker inherits the value from env; the argument contains only its name.
    id=$(MYSQL_ROOT_PASSWORD="$VERIFIER_MYSQL_PASSWORD" docker run -d --name "$prefix-mysql" \
      --label "$owner_label=$VERIFIER_OWNER_ID" --label "$managed_label=isolated-harness" \
      -e MYSQL_ROOT_PASSWORD -p "$mysql_port:3306" \
      mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci)
    created+=("$id")
    id=$(docker run -d --name "$prefix-redis-mail" \
      --label "$owner_label=$VERIFIER_OWNER_ID" --label "$managed_label=isolated-harness" \
      -p 16379:6379 redis:7-alpine)
    created+=("$id")
    id=$(docker run -d --name "$prefix-redis-coupon" \
      --label "$owner_label=$VERIFIER_OWNER_ID" --label "$managed_label=isolated-harness" \
      -p 16380:6379 redis:7-alpine)
    created+=("$id")
    for _ in $(seq 1 60); do
      docker logs "$prefix-mysql" 2>&1 | grep -q "ready for connections.*port: 3306  MySQL" &&
        docker exec "$prefix-mysql" sh -c \
          'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqladmin ping -uroot --silent' \
          >/dev/null 2>&1 && { echo ready; exit 0; }
      sleep 2
    done
    echo "mysql not ready" >&2; exit 1 ;;
  down)
    verify_owned
    docker stop "${containers[@]}" >/dev/null ;;
  remove)
    verify_owned
    for name in "${containers[@]}"; do
      [[ "$(docker inspect --format '{{.State.Running}}' "$name")" == "false" ]] ||
        { echo "stop container before removal: $name" >&2; exit 2; }
    done
    docker rm "${containers[@]}" >/dev/null ;;
  *) echo "usage: $0 up|down|remove" >&2; exit 1 ;;
esac
