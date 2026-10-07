#!/usr/bin/env bash
set -euo pipefail
# 이번 검증 소유의 격리 인프라(MySQL 3307, redis 16379/16380). 기존 givemeticon-* 컨테이너는 건드리지 않는다.
# 사용: start-infra.sh up|down. 비밀번호는 VERIFIER_MYSQL_PASSWORD env(없으면 거부).
prefix=${VERIFIER_PREFIX:-verifier-iso}
mysql_port=${VERIFIER_MYSQL_PORT:-3307}
case "${1:-}" in
  up)
    : "${VERIFIER_MYSQL_PASSWORD:?set VERIFIER_MYSQL_PASSWORD}"
    for p in "$mysql_port" 16379 16380; do
      ! lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 || { echo "port $p in use" >&2; exit 2; }
    done
    docker run -d --name "$prefix-mysql" -e MYSQL_ROOT_PASSWORD="$VERIFIER_MYSQL_PASSWORD" -p "$mysql_port:3306" \
      mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null
    docker run -d --name "$prefix-redis-mail" -p 16379:6379 redis:7-alpine >/dev/null
    docker run -d --name "$prefix-redis-coupon" -p 16380:6379 redis:7-alpine >/dev/null
    for _ in $(seq 1 60); do
      # 이미지 초기화 중의 임시 서버(port: 0)와 최종 서버(port: 3306)를 구분한다.
      docker logs "$prefix-mysql" 2>&1 | grep -q "ready for connections.*port: 3306  MySQL" && \
        docker exec "$prefix-mysql" mysqladmin ping -uroot -p"$VERIFIER_MYSQL_PASSWORD" --silent >/dev/null 2>&1 && { echo ready; exit 0; }
      sleep 2
    done
    echo "mysql not ready" >&2; exit 1 ;;
  down)
    docker rm -f "$prefix-mysql" "$prefix-redis-mail" "$prefix-redis-coupon" >/dev/null ;;
  *) echo "usage: $0 up|down" >&2; exit 1 ;;
esac
