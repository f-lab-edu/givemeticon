#!/usr/bin/env bash
# Single entry point for coupon-lab verification. Run from coupon-lab/.
#   verify.sh compile
#   verify.sh unit [--tests <pattern>]
#   verify.sh bundle <consistency|load|bottleneck|recovery|fairness> [--stage sN]
#   verify.sh load --stage sN --profile <ramping|spike|ramping-rows> [--runs N] [--k6 host|container] [--rows N] [--max-vus N]
#                  [--flush-log 1|2] [--pool N]    (--flush-log 2 relaxes durability: a DIAGNOSTIC, never a configuration)
#                  (tests B and C: k6 against docker compose, see docs/stages.md)
#   verify.sh reprocess evidence/<stage>/load/<profile>/<dir>        (test B: re-aggregate a saved run into a new -reprocessed dir)
# Last stdout line:
#   VERIFY <tier> <PASS|FAIL|INFRA_DOWN|BUILD_ERROR|TIMEOUT|NO_TESTS> passed=N failed=N skipped=N duration=Ns sha=<short> dirty=<bool> report=<path>
# Exit codes: 0 PASS, 1 FAIL, 2 usage/config error, 3 INFRA_DOWN, 4 TIMEOUT, 5 BUILD_ERROR, 6 NO_TESTS.
# For `load`: PASS = valid run (first stage without dropped iterations >= 1% and DB rows == 200 responses),
# FAIL = invalid run, INFRA_DOWN = docker/compose/health/seed problem, BUILD_ERROR = k6 or postprocess failed.
# Later stages that saturate do not invalidate a run; they are flagged saturated in load.json.
set -euo pipefail

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 2; }

BUNDLES="consistency load bottleneck recovery fairness"
# Connection pool size of the load-test app: the environment, then verify.sh load --pool N, then 10. Set before the arguments are parsed.
LOAD_HIKARI_POOL_SIZE=${HIKARI_POOL_SIZE:-10}

usage() {
    echo "usage: scripts/verify.sh compile | unit [--tests <pattern>] | bundle <$(echo "$BUNDLES" | tr ' ' '|')> [--stage sN]" >&2
    echo "       scripts/verify.sh load --stage sN --profile ramping|spike|ramping-rows [--runs N] [--k6 host|container] [--rows N] [--max-vus N] [--flush-log 1|2] [--pool N]" >&2
    echo "       scripts/verify.sh reprocess evidence/<stage>/load/<profile>/<dir>" >&2
    exit 2
}

# A stage given with --stage must exist on this branch; stop here instead of failing later in the Spring context.
require_stage_class() {
    local n=${1#s} found
    found=$(find src/main/java -type f -name "S${n}CouponIssueService.java" 2>/dev/null | head -n 1 || true)
    [ -n "$found" ] || { echo "S${n}CouponIssueService not found on this branch" >&2; exit 2; }
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
        require_stage_class "$stage"
        tier="bundle-$bundle"
        ;;
    load)
        profile=""
        runs=1
        k6_mode=host
        rows=1
        max_vus=""
        flush_log=1
        pool=""
        while [ $# -gt 0 ]; do
            [ $# -ge 2 ] || usage
            case "$1" in
                --stage) stage=$2 ;;
                --profile) profile=$2 ;;
                --runs) runs=$2 ;;
                --k6) k6_mode=$2 ;;
                --rows) rows=$2 ;;
                --max-vus) max_vus=$2 ;;
                --flush-log) flush_log=$2 ;;
                --pool) pool=$2 ;;
                *) usage ;;
            esac
            shift 2
        done
        case "$k6_mode" in host|container) ;; *) usage ;; esac
        [ -n "$stage" ] && [ -n "$profile" ] || usage
        [[ "$stage" =~ ^s[0-9]+$ ]] || { echo "invalid stage: $stage (expected sN)" >&2; exit 2; }
        case "$profile" in ramping|spike|ramping-rows) ;; *) usage ;; esac
        [[ "$rows" =~ ^[1-9][0-9]*$ ]] && [ "$rows" -le 64 ] || usage
        [ "$rows" -eq 1 ] || [ "$profile" = ramping-rows ] || { echo "--rows $rows needs --profile ramping-rows" >&2; exit 2; }
        [ -z "$max_vus" ] || [[ "$max_vus" =~ ^[1-9][0-9]*$ ]] || usage
        case "$flush_log" in 1|2) ;; *) usage ;; esac
        [ -z "$pool" ] || { [[ "$pool" =~ ^[1-9][0-9]*$ ]] && [ "$pool" -le 200 ]; } || usage
        [ -z "$pool" ] || LOAD_HIKARI_POOL_SIZE=$pool
        [[ "$runs" =~ ^[1-9][0-9]*$ ]] || usage
        require_stage_class "$stage"
        tier="load-$profile"
        # evidence/<stage>/load/<variant>/: the profile plus whatever distinguishes the run (the row count is part of ramping-rows)
        variant=$profile
        [ "$profile" != ramping-rows ] || variant="ramping-rows$rows"
        [ "$flush_log" = 1 ] || variant="$variant-flush$flush_log"
        [ "$LOAD_HIKARI_POOL_SIZE" = 10 ] || variant="$variant-pool$LOAD_HIKARI_POOL_SIZE"
        ;;
    reprocess)
        [ $# -eq 1 ] || usage
        source_dir=${1%/}
        [[ "$source_dir" =~ ^evidence/(s[0-9]+)/load/((ramping|spike)(-[a-z0-9]+)*)/[^/]+$ ]] \
            || { echo "expected evidence/<stage>/load/<profile>/<dir>, got: $1" >&2; exit 2; }
        stage=${BASH_REMATCH[1]}
        profile=${BASH_REMATCH[2]}   # the directory name, e.g. ramping-rows4; the result goes next to the source
        [ -f "$source_dir/load.json" ] && [ -f "$source_dir/raw.json.gz" ] \
            || { echo "$source_dir needs load.json and raw.json.gz" >&2; exit 2; }
        tier=load-reprocess
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

# Writes the report, prints the VERIFY line and exits. Reads passed/failed/evidence_dirs/failures of the caller.
load_finish() {   # <status> <exit code>
    local ended_epoch
    ended_epoch=$(date +%s)
    python3 -I - "$tier" "$stage" "$sha" "$dirty" "$1" "$passed" "$failed" "$started_at" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$((ended_epoch - started_epoch))" "$2" "$report" \
        "${evidence_dirs[*]:-}" "$(IFS=';'; echo "${failures[*]:-}")" <<'PY'
import json, sys
(tier, stage, sha, dirty, status, passed, failed, started_at, ended_at, duration, exit_code, report,
 evidence_dirs, failures) = sys.argv[1:15]
dirs = evidence_dirs.split()
with open(report, "w") as f:
    json.dump({
        "tier": tier, "stage": stage, "sha": sha, "dirty": dirty == "true", "status": status,
        "passed": int(passed), "failed": int(failed), "skipped": 0,
        "failures": [{"class": "load", "method": "run", "message": m} for m in failures.split(";") if m],
        "started_at": started_at, "ended_at": ended_at, "duration_s": int(duration), "exit_code": int(exit_code),
        "gradle_exit_code": None, "evidence_dir": dirs[-1] if dirs else None, "evidence_dirs": dirs,
    }, f, indent=2)
    f.write("\n")
PY
    echo "VERIFY $tier $1 passed=$passed failed=$failed skipped=0 duration=$((ended_epoch - started_epoch))s sha=$sha dirty=$dirty report=$report"
    exit "$2"
}

LOAD_PREFIX=coupon-lab-load-
LOAD_NETWORK=coupon-lab-load_default
K6_IMAGE=${K6_IMAGE:-grafana/k6:latest}
LOAD_HEALTH_TIMEOUT_S=${LOAD_HEALTH_TIMEOUT_S:-60}

# Every 2 s one line with MySQL's cumulative InnoDB lock and fsync counters:
#   {"time": <epoch>, "status": {"Innodb_row_lock_waits": n, ...}}
# postprocess.py turns them into per-stage deltas (test C, experiment a). Needs load_mysql (defined inside run_load);
# run in the background during k6 and killed afterwards.
load_sample_innodb() {
    local out=$1 started now
    while :; do
        started=$(date +%s)
        load_mysql "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_avg','Innodb_row_lock_time_max','Innodb_row_lock_current_waits','Innodb_data_fsyncs','Innodb_os_log_fsyncs','Threads_connected')" 2>/dev/null \
            | awk -F'\t' -v t="$(date +%s)" 'NF == 2 { body = body (body == "" ? "" : ",") "\"" $1 "\":" $2 } END { if (body != "") printf "{\"time\":%s,\"status\":{%s}}\n", t, body }' >>"$out"
        now=$(date +%s)
        [ $((now - started)) -ge 2 ] || sleep $((2 - now + started))
    done
}

# Position of this run within a batch of back-to-back runs (1..n): the counter file holds "<count> <start epoch of the last
# run>" and starts again at 1 when the previous run began more than LOAD_SESSION_GAP_MIN (default 60) minutes ago.
load_next_run_order() {
    local file=build/verify/load-run-counter count=0 last=0 now
    now=$(date +%s)
    [ ! -f "$file" ] || read -r count last <"$file" 2>/dev/null
    [[ "$count" =~ ^[0-9]+$ && "$last" =~ ^[0-9]+$ ]] || { count=0; last=0; }
    if [ $((now - last)) -gt $((${LOAD_SESSION_GAP_MIN:-60} * 60)) ]; then count=1; else count=$((count + 1)); fi
    echo "$count $now" >"$file"
    echo "$count"
}

# Every 2 s one line per container into <outfile>: {"time": <epoch>, "stat": <docker stats object>}. Run in the
# background during k6 and killed afterwards; postprocess.py turns the app and mysql lines into per-stage CPU figures
# (redis lines are kept in stats.jsonl for reading by hand and are not aggregated).
load_sample_stats() {
    local out=$1 started now
    while :; do
        started=$(date +%s)
        docker stats --no-stream --format json "${LOAD_PREFIX}app" "${LOAD_PREFIX}mysql" "${LOAD_PREFIX}redis" 2>/dev/null | while IFS= read -r line; do
            [ -z "$line" ] || printf '{"time":%s,"stat":%s}\n' "$(date +%s)" "$line" >>"$out"
        done
        now=$(date +%s)
        [ $((now - started)) -ge 2 ] || sleep $((2 - now + started))
    done
}

# verify.sh load: for each run, compose up (app + mysql), seed one event, k6 from the host, postprocess,
# count issued rows, compose down -v, copy the result to evidence/<stage>/load/<profile>/. Stops at the first run
# that is not PASS. No container other than coupon-lab-load-* may be running. With --k6 host (default) k6 must be
# installed on the host and reaches the app through the published port; with --k6 container k6 runs from $K6_IMAGE
# inside the compose network against http://app:8080 and writes its output to a bind-mounted directory.
run_load() {
    set +e
    local compose=(docker compose -p coupon-lab-load -f docker-compose.load.yml)
    local compose_up=false run_status="" run_note="" work="" run_evidence="" sampler_pid="" innodb_pid=""
    local passed=0 failed=0 n evidence_dirs=() failures=()

    load_cleanup() {
        if [ -n "$sampler_pid" ]; then kill "$sampler_pid" 2>/dev/null; wait "$sampler_pid" 2>/dev/null; sampler_pid=""; fi
        if [ -n "$innodb_pid" ]; then kill "$innodb_pid" 2>/dev/null; wait "$innodb_pid" 2>/dev/null; innodb_pid=""; fi
        [ "$k6_mode" != container ] || docker rm -f "${LOAD_PREFIX}k6" >/dev/null 2>&1
        [ "$compose_up" = true ] || return 0
        compose_up=false
        "${compose[@]}" down -v --remove-orphans >>"$log" 2>&1 \
            || echo "WARN: docker compose down failed; remove the ${LOAD_PREFIX}* containers and volumes by hand" >&2
    }
    trap load_cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    : >"$log"
    # 1. preconditions: k6, docker daemon, idle machine
    if [ "$k6_mode" = host ] && ! command -v k6 >/dev/null; then
        echo "k6 not found; install it on the host first (or use --k6 container)" >&2
        exit 2
    fi
    if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
        echo "docker daemon not reachable" | tee -a "$log"
        load_finish INFRA_DOWN 3
    fi
    local others
    others=$(docker ps --format '{{.Names}}' | grep -v "^$LOAD_PREFIX" || true)
    if [ -n "$others" ]; then
        echo "running containers other than ${LOAD_PREFIX}*:" >&2
        sed 's/^/  /' <<<"$others" >&2
        echo "stop other containers first; measurements must run on an idle machine" >&2
        exit 2
    fi

    load_mysql() {   # <sql>: rows without header; the password travels in the environment, not in argv
        MYSQL_PWD="$COUPON_LAB_DB_PASSWORD" docker exec -e MYSQL_PWD "${LOAD_PREFIX}mysql" \
            mysql -h 127.0.0.1 -uroot -N -B coupon_lab -e "$1" 2>>"$log"
    }
    load_inspect() { docker inspect -f "$1" "${LOAD_PREFIX}$2" 2>/dev/null; }

    # sets run_status (PASS|FAIL|INFRA_DOWN|BUILD_ERROR), run_note, work
    load_body() {
        work="build/load/run-$1"
        rm -rf "$work"
        mkdir -p "$work"
        mkdir -p "$work/gc"
        export COUPON_LAB_DB_PASSWORD COUPON_STRATEGY=$stage HIKARI_POOL_SIZE=$LOAD_HIKARI_POOL_SIZE FLUSH_LOG=$flush_log LOAD_GC_DIR="$PWD/$work/gc"
        local run_order up_epoch
        run_order=$(load_next_run_order)
        COUPON_LAB_DB_PASSWORD=$(python3 -I -c 'import secrets; print(secrets.token_hex(12))')

        local k6_version k6_env=()
        if [ "$k6_mode" = container ]; then
            if ! docker image inspect "$K6_IMAGE" >/dev/null 2>&1 && ! docker pull "$K6_IMAGE" >>"$log" 2>&1; then
                run_status=INFRA_DOWN; run_note="cannot pull $K6_IMAGE (see $log)"; return
            fi
            k6_version=$(docker run --rm "$K6_IMAGE" version 2>>"$log" | head -n 1 | awk '{print $2}')
            k6_env=(--env "k6_image=$K6_IMAGE")
        else
            k6_version=$(k6 version 2>/dev/null | head -n 1 | awk '{print $2}')
        fi

        # 2. environment
        "${compose[@]}" down -v --remove-orphans >>"$log" 2>&1
        compose_up=true
        echo "run $1/$runs: docker compose up (first image build takes a few minutes)"
        if ! "${compose[@]}" up -d --build >>"$log" 2>&1; then
            run_status=INFRA_DOWN; run_note="docker compose up failed (see $log)"; return
        fi
        up_epoch=$(date +%s)
        local waited=0 health="" redis_health=""
        while [ "$waited" -lt "$LOAD_HEALTH_TIMEOUT_S" ]; do
            health=$(load_inspect '{{.State.Health.Status}}' app)
            redis_health=$(load_inspect '{{.State.Health.Status}}' redis)
            [ "$health" != healthy ] || [ "$redis_health" != healthy ] || break
            sleep 2
            waited=$((waited + 2))
        done
        if [ "$health" != healthy ] || [ "$redis_health" != healthy ]; then
            docker logs --tail 40 "${LOAD_PREFIX}app" >>"$log" 2>&1
            run_status=INFRA_DOWN; run_note="app (${health:-?}) or redis (${redis_health:-?}) not healthy after ${LOAD_HEALTH_TIMEOUT_S}s (see $log)"; return
        fi

        # 3. seed the events (one per row of the test-C spread; ramping measures the issue path only, spike the sold-out path)
        local stock event_ids seed_sql="" i vus_max_default
        stock=$(python3 -I -c 'import json, sys; print(json.load(open("scripts/load/profiles.json"))["profiles"][sys.argv[1]]["stock"])' "$profile")
        vus_max_default=$(python3 -I -c 'import json; print(json.load(open("scripts/load/profiles.json"))["vus"]["max"])')
        for i in $(seq 1 "$rows"); do
            seed_sql="$seed_sql INSERT INTO coupon_event (name, total_quantity, remaining_quantity, created_at) VALUES ('load-$profile-$stage-$i', $stock, $stock, NOW(6)); SELECT LAST_INSERT_ID();"
        done
        event_ids=$(load_mysql "$seed_sql" | paste -sd, -)
        if ! [[ "$event_ids" =~ ^[0-9]+(,[0-9]+)*$ ]] || [ "$(awk -F, '{ print NF }' <<<"$event_ids")" -ne "$rows" ]; then
            run_status=INFRA_DOWN; run_note="seeding $rows event(s) failed (got '$event_ids'; see $log)"; return
        fi

        # InnoDB settings that decide how expensive a commit is, recorded with the run
        local mysql_env=() var_name var_value
        while IFS=$'\t' read -r var_name var_value; do
            [ -z "$var_name" ] || mysql_env+=(--env "$var_name=$var_value")
        done < <(load_mysql "SHOW VARIABLES WHERE Variable_name IN ('innodb_flush_log_at_trx_commit','innodb_buffer_pool_size','innodb_log_file_size','innodb_redo_log_capacity')")

        # The diagnostic knob must really be in effect, otherwise the run measures something else than its path says.
        local flush_effective
        flush_effective=$(load_mysql "SELECT @@GLOBAL.innodb_flush_log_at_trx_commit")
        if [ "$flush_effective" != "$flush_log" ]; then
            run_status=INFRA_DOWN; run_note="mysql runs innodb_flush_log_at_trx_commit=${flush_effective:-?}, requested $flush_log (see $log)"; return
        fi

        # 4. k6, postprocess, DB check
        local container_epoch clock_offset="" minutes_up
        container_epoch=$(docker exec "${LOAD_PREFIX}app" date +%s 2>/dev/null)
        [[ "$container_epoch" =~ ^[0-9]+$ ]] && clock_offset=$((container_epoch - $(date +%s)))
        minutes_up=$(awk -v now="$(date +%s)" -v up="$up_epoch" 'BEGIN { printf "%.1f", (now - up) / 60 }')
        echo "run $1/$runs: k6 ($k6_mode) $profile on $stage, events $event_ids, stock $stock each${max_vus:+, maxVUs $max_vus}"
        : >"$work/stats.jsonl"
        load_sample_stats "$work/stats.jsonl" &
        sampler_pid=$!
        : >"$work/innodb.jsonl"
        load_sample_innodb "$work/innodb.jsonl" &
        innodb_pid=$!
        if [ "$k6_mode" = container ]; then
            docker run --rm --name "${LOAD_PREFIX}k6" --network "$LOAD_NETWORK" --user "$(id -u):$(id -g)" \
                -v "$PWD/scripts/load:/scripts:ro" -v "$PWD/$work:/out" \
                -e BASE_URL=http://app:8080 -e EVENT_IDS="$event_ids" -e PROFILE="$profile" ${max_vus:+-e MAX_VUS=$max_vus} \
                "$K6_IMAGE" run --out json=/out/raw.json.gz --summary-export /out/summary.json /scripts/issue.js >>"$log" 2>&1
        else
            k6 run --out json="$work/raw.json.gz" --summary-export "$work/summary.json" \
                -e BASE_URL=http://127.0.0.1:18080 -e EVENT_IDS="$event_ids" -e PROFILE="$profile" ${max_vus:+-e MAX_VUS=$max_vus} \
                scripts/load/issue.js >>"$log" 2>&1
        fi
        local k6_rc=$?
        kill "$sampler_pid" 2>/dev/null; wait "$sampler_pid" 2>/dev/null; sampler_pid=""
        kill "$innodb_pid" 2>/dev/null; wait "$innodb_pid" 2>/dev/null; innodb_pid=""
        if [ "$k6_rc" -ne 0 ]; then
            # docker itself failed (125 daemon error, 126/127 cannot start the command) is infra, not a k6 script error
            if [ "$k6_mode" = container ] && [ "$k6_rc" -ge 125 ] && [ "$k6_rc" -le 127 ]; then
                run_status=INFRA_DOWN; run_note="docker run of the k6 container failed with $k6_rc (see $log)"; return
            fi
            run_status=BUILD_ERROR; run_note="k6 exited $k6_rc (see $log)"; return
        fi
        local issued_rows nano cpus_app cpus_mysql cpus_redis redis_image heap hikari
        issued_rows=$(load_mysql "SELECT COUNT(*) FROM coupon_issue WHERE event_id IN ($event_ids)")
        if ! [[ "$issued_rows" =~ ^[0-9]+$ ]]; then
            run_status=INFRA_DOWN; run_note="counting coupon_issue rows failed (see $log)"; return
        fi
        # S3 only: the decision counter of the seeded events (informational; the validity rule stays db rows == http issued)
        local redis_counter_arg=() redis_counter id value
        if [ "$stage" = s3 ]; then
            redis_counter=0
            for id in ${event_ids//,/ }; do
                value=$(docker exec "${LOAD_PREFIX}redis" redis-cli --raw GET "coupon:event:$id:issued" 2>>"$log")
                [[ "${value:-0}" =~ ^[0-9]+$ ]] || { run_status=INFRA_DOWN; run_note="reading the redis counter of event $id failed (got '$value'; see $log)"; return; }
                redis_counter=$((redis_counter + ${value:-0}))
            done
            redis_counter_arg=(--redis-counter "$redis_counter")
        fi
        nano=$(load_inspect '{{.HostConfig.NanoCpus}}' app); [[ "$nano" =~ ^[0-9]+$ ]] || nano=0; cpus_app=$((nano / 1000000000))
        nano=$(load_inspect '{{.HostConfig.NanoCpus}}' mysql); [[ "$nano" =~ ^[0-9]+$ ]] || nano=0; cpus_mysql=$((nano / 1000000000))
        heap=$(load_inspect '{{json .Config.Entrypoint}}' app | grep -o -- '-Xmx[0-9]*[kKmMgG]' | head -n 1 | sed 's/^-Xmx//')
        hikari=$(load_inspect '{{range .Config.Env}}{{println .}}{{end}}' app | sed -n 's/^SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=//p')
        nano=$(load_inspect '{{.HostConfig.NanoCpus}}' redis); [[ "$nano" =~ ^[0-9]+$ ]] || nano=0; cpus_redis=$((nano / 1000000000))
        redis_image=$(load_inspect '{{.Config.Image}}' redis)
        python3 -I scripts/load/postprocess.py --raw "$work/raw.json.gz" --profile "$profile" --stage "$stage" \
            --db-issued-rows "$issued_rows" ${redis_counter_arg[@]+"${redis_counter_arg[@]}"} --out "$work/load.json" --filtered-raw "$work/raw-filtered.json.gz" \
            --stats "$work/stats.jsonl" --summary "$work/summary.json" --gc-log "$work/gc/gc.log" --innodb "$work/innodb.jsonl" \
            --run-order "$run_order" --minutes-since-up "$minutes_up" ${clock_offset:+--clock-offset-s "$clock_offset"} \
            --rows "$rows" ${max_vus:+--max-vus "$max_vus"} --env "max_vus=${max_vus:-$vus_max_default}" \
            --env "app_cpus=$cpus_app" --env "mysql_cpus=$cpus_mysql" --env "redis_image=$redis_image" --env "redis_cpus=$cpus_redis" --env "jvm_heap=$heap" \
            --env "hikari_pool_size=$hikari" --env "k6_version=$k6_version" --env "k6_mode=$k6_mode" ${k6_env[@]+"${k6_env[@]}"} ${mysql_env[@]+"${mysql_env[@]}"} \
            >>"$log" 2>&1
        if [ $? -ne 0 ]; then
            run_status=BUILD_ERROR; run_note="postprocess failed (see $log)"; return
        fi
        local summary
        summary=$(python3 -I -c '
import json, sys
d = json.load(open(sys.argv[1]))
print("sustained_limit_rps=%s first_violation=%s stalls=%d stage0_dropped_ratio=%s db_issued_rows=%s http_issued=%s valid=%s" % (
    d["sustained_limit_rps"], d["limit_rps_first_violation"], len(d["stalls"]), d["validity"]["stage0_dropped_ratio"],
    d["db"]["issued_rows"], d["db"]["http_issued"],
    str(d["validity"]["valid"]).lower()))
print("PASS" if d["validity"]["valid"] else "FAIL")' "$work/load.json")
        echo "run $1/$runs: $(head -n 1 <<<"$summary")"
        run_status=$(tail -n 1 <<<"$summary")
        [ "$run_status" = PASS ] || run_note="measurement invalid: $(head -n 1 <<<"$summary")"
    }

    for n in $(seq 1 "$runs"); do
        run_status=""; run_note=""
        load_body "$n"
        load_cleanup   # 5. always, before anything else touches the result
        # 6. keep what exists, also for an invalid run
        run_evidence=""
        if [ -f "$work/load.json" ]; then
            run_evidence="evidence/$stage/load/$variant/$(date -u +%Y%m%d-%H%M%S)-$sha"
            [ "$dirty" = false ] || run_evidence="$run_evidence-dirty"
            mkdir -p "$run_evidence"
            cp "$work/load.json" "$run_evidence/"
            [ ! -f "$work/summary.json" ] || cp "$work/summary.json" "$run_evidence/"
            [ ! -f "$work/stats.jsonl" ] || cp "$work/stats.jsonl" "$run_evidence/"
            [ ! -f "$work/innodb.jsonl" ] || cp "$work/innodb.jsonl" "$run_evidence/"
            [ ! -f "$work/gc/gc.log" ] || cp "$work/gc/gc.log" "$run_evidence/"
            [ ! -f "$work/raw-filtered.json.gz" ] || cp "$work/raw-filtered.json.gz" "$run_evidence/raw.json.gz"
            evidence_dirs+=("$run_evidence")
            echo "run $n/$runs: evidence in $run_evidence"
        fi
        [ -z "$run_note" ] || { echo "run $n/$runs: $run_status - $run_note"; failures+=("run $n: $run_note"); }
        case "$run_status" in
            PASS) passed=$((passed + 1)) ;;
            FAIL) failed=$((failed + 1)); break ;;
            *) break ;;
        esac
    done

    case "$run_status" in
        PASS) load_finish PASS 0 ;;
        FAIL) load_finish FAIL 1 ;;
        INFRA_DOWN) load_finish INFRA_DOWN 3 ;;
        *) load_finish BUILD_ERROR 5 ;;
    esac
}

# verify.sh reprocess <evidence dir>: aggregate a saved run again with the current rules. The source stays untouched;
# the result goes to a new <ts>-<sha>[-dirty]-reprocessed directory next to it.
run_reprocess() {
    set +e
    local passed=0 failed=0 evidence_dirs=() failures=() dest tmp=build/load/reprocess verdict
    : >"$log"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    if ! python3 -I scripts/load/postprocess.py --reprocess-from "$source_dir" --out "$tmp/load.json" >>"$log" 2>&1; then
        failures+=("postprocess failed for $source_dir (see $log)")
        echo "postprocess failed for $source_dir (see $log)"
        load_finish BUILD_ERROR 5
    fi
    dest="evidence/$stage/load/$profile/$(date -u +%Y%m%d-%H%M%S)-$sha"
    [ "$dirty" = false ] || dest="$dest-dirty"
    dest="$dest-reprocessed"
    if [ -e "$dest" ]; then echo "$dest already exists; evidence is never overwritten" >&2; exit 2; fi
    mkdir -p "$dest"
    cp "$tmp/load.json" "$dest/"
    [ ! -f "$source_dir/summary.json" ] || cp "$source_dir/summary.json" "$dest/"
    [ ! -f "$source_dir/stats.jsonl" ] || cp "$source_dir/stats.jsonl" "$dest/"
    [ ! -f "$source_dir/innodb.jsonl" ] || cp "$source_dir/innodb.jsonl" "$dest/"
    [ ! -f "$source_dir/gc.log" ] || cp "$source_dir/gc.log" "$dest/"
    evidence_dirs+=("$dest")
    verdict=$(python3 -I -c '
import json, sys
d = json.load(open(sys.argv[1]))
print("sustained_limit_rps=%s first_violation=%s stalls=%d stage0_dropped_ratio=%s db_issued_rows=%s http_issued=%s valid=%s" % (
    d["sustained_limit_rps"], d["limit_rps_first_violation"], len(d["stalls"]), d["validity"]["stage0_dropped_ratio"],
    d["db"]["issued_rows"], d["db"]["http_issued"],
    str(d["validity"]["valid"]).lower()))
print("PASS" if d["validity"]["valid"] else "FAIL")' "$dest/load.json")
    echo "reprocessed $source_dir: $(head -n 1 <<<"$verdict")"
    echo "evidence in $dest"
    if [ "$(tail -n 1 <<<"$verdict")" = PASS ]; then
        passed=1
        load_finish PASS 0
    fi
    failed=1
    failures+=("run invalid: $(head -n 1 <<<"$verdict")")
    load_finish FAIL 1
}

case "$mode" in
    load) run_load ;;
    reprocess) run_reprocess ;;
esac

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
