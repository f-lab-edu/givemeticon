#!/usr/bin/env bash
set -uo pipefail

# Usage: mysql-io-snapshot.sh > snapshot.txt
# innodb_flush_log_at_trx_commit 등 커밋 내구성 설정은 절대 바꾸지 않는다 - 이미 노출된
# 전역 상태·performance_schema·docker stats만 읽는다(순수 관찰). before/after 두 번 찍어
# 델타를 구하는 용도.
container=${MYSQL_CONTAINER:-givemeticon-mysql}

docker exec "$container" sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "
SELECT '"'"'Innodb_os_log_fsyncs'"'"', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='"'"'Innodb_os_log_fsyncs'"'"';
SELECT '"'"'Innodb_os_log_pending_fsyncs'"'"', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='"'"'Innodb_os_log_pending_fsyncs'"'"';
SELECT '"'"'Innodb_log_writes'"'"', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='"'"'Innodb_log_writes'"'"';
SELECT '"'"'redo_log_file_count_star'"'"', COUNT_STAR FROM performance_schema.file_summary_by_event_name WHERE EVENT_NAME LIKE '"'"'%innodb_log_file%'"'"';
SELECT '"'"'redo_log_file_sum_timer_wait_ps'"'"', SUM_TIMER_WAIT FROM performance_schema.file_summary_by_event_name WHERE EVENT_NAME LIKE '"'"'%innodb_log_file%'"'"';
SELECT '"'"'ibdata_count_star'"'"', COUNT_STAR FROM performance_schema.file_summary_by_event_name WHERE EVENT_NAME LIKE '"'"'%innodb_data_file%'"'"';
SELECT '"'"'ibdata_sum_timer_wait_ps'"'"', SUM_TIMER_WAIT FROM performance_schema.file_summary_by_event_name WHERE EVENT_NAME LIKE '"'"'%innodb_data_file%'"'"';
"' 2>/dev/null

blockio=$(docker stats --no-stream --format '{{.BlockIO}}' "$container" 2>/dev/null)
echo -e "docker_block_io\t$blockio"
