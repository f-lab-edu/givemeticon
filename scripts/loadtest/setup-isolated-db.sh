#!/usr/bin/env bash
set -euo pipefail

container=${MYSQL_CONTAINER:-givemeticon-mysql}
database=${LOADTEST_DB_NAME:-givemeticon_loadtest}
root_dir=$(cd "$(dirname "$0")/../.." && pwd)
schema_file="$root_dir/scripts/loadtest/mysql-coupon-schema.sql"

if ! [[ "$database" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "LOADTEST_DB_NAME may contain only letters, digits, and underscores" >&2
  exit 2
fi

# 다른 스키마를 삭제하지 않는다. CREATE IF NOT EXISTS와 테스트 전용 DDL만 수행한다.
docker exec "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'CREATE DATABASE IF NOT EXISTS $database CHARACTER SET utf8mb4'"
docker exec -i "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $database" < "$schema_file"
docker exec "$container" sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -Nse 'SELECT DATABASE(), VERSION(), @@transaction_isolation, @@max_connections' $database"
