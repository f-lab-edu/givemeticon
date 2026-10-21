#!/usr/bin/env python3
"""고빈도 관찰기(기존 monitor-admission.sh는 docker exec 반복으로 표본 간격이 3~8초였다).
사용: fast-sampler.py <run_dir> <mysql_container> <database> <event_id> <port_a> <port_b> [interval_s=0.2]
  - prom 표본: 두 앱 /actuator/prometheus 에서 Hikari active/idle/max/pending, acquire count/sum/max 를 fast-prom.csv 로 기록
  - DB 관찰: 상주 mysql 세션으로 커밋된 상태를 조회(별도 연결, 자동 commit)하여 fast-db.csv 로 기록
      host_before_ms,host_after_ms,server_ms,final_visible,unresolved,total   (server_ms = DB 서버 NOW(6))
    server_ms - (before+after)/2 가 시계 offset 추정치(오차 ±(after-before)/2)다.
  - 쿠폰 지표: 두 앱의 coupon_admission_*, coupon_issuance_* (히스토그램 _bucket 제외) 전 시리즈를 fast-coupon-prom.csv 로 기록
      host_ms,app,series,value   (series = 지표명{태그}, 예: caller/outcome 태그별 select_for_update _count/_sum/_max)
  - MySQL 락: fast-db.csv 세션과 분리된 별도 mysql 세션·스레드에서 Innodb_row_lock_waits/Innodb_row_lock_time(ms, 누적)/
    performance_schema.data_lock_waits 건수를 fast-mysql-locks.csv 로 기록. 이 조회가 실패해도 fast-db.csv 기록은 계속된다.
SIGTERM 으로 종료한다. 비밀번호는 컨테이너 env 에서 읽어 인자/로그에 노출하지 않는다.
"""
import csv, queue, re, signal, subprocess, sys, threading, time, urllib.request

run_dir, container, database, event_id, pa, pb = sys.argv[1:7]
interval = float(sys.argv[7]) if len(sys.argv) > 7 else 0.2
stop = threading.Event()
signal.signal(signal.SIGTERM, lambda *a: stop.set()); signal.signal(signal.SIGINT, lambda *a: stop.set())
M = {k: re.compile(r'^' + k + r'(\{[^}]*\})? ([0-9.eE+-]+)$') for k in (
    'hikaricp_connections_active', 'hikaricp_connections_idle', 'hikaricp_connections_max', 'hikaricp_connections_pending',
    'hikaricp_connections_acquire_seconds_count', 'hikaricp_connections_acquire_seconds_sum', 'hikaricp_connections_acquire_seconds_max',
    'hikaricp_connections_timeout_total')}

COUPON = re.compile(r'^(coupon_(?:admission|issuance)_\S+?)(\{[^}]*\})? (\S+)$')

def prom_loop():
    f = open(f"{run_dir}/fast-prom.csv", "w", newline=""); w = csv.writer(f)
    cf = open(f"{run_dir}/fast-coupon-prom.csv", "w", newline=""); cw = csv.writer(cf)
    cw.writerow(["host_ms", "app", "series", "value"])
    w.writerow(["host_ms", "app", "active", "idle", "max", "pending", "acq_count", "acq_sum_s", "acq_max_s", "timeout_total", "scrape_ms"])
    while not stop.is_set():
        t0 = time.time()
        for name, port in (("app1", pa), ("app2", pb)):
            s = time.time()
            try:
                body = urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/prometheus", timeout=1).read().decode()
            except Exception:
                w.writerow([int(s * 1000), name] + ["ERR"] * 9); continue
            v = {}
            for line in body.splitlines():
                cm = COUPON.match(line)
                if cm and not cm.group(1).endswith("_bucket"):
                    cw.writerow([int(s * 1000), name, cm.group(1) + (cm.group(2) or ""), cm.group(3)])
                for k, rx in M.items():
                    m = rx.match(line)
                    if m: v[k] = v.get(k, 0.0) + float(m.group(2)) if k != 'hikaricp_connections_acquire_seconds_max' else max(v.get(k, 0.0), float(m.group(2)))
            w.writerow([int(s * 1000), name] + [v.get(k, "NA") for k in M] + [int((time.time() - s) * 1000)])
        f.flush(); cf.flush()
        stop.wait(max(0, interval - (time.time() - t0)))
    f.close(); cf.close()

def db_loop():
    # Resolve the password only inside the container, never in host argv/stdout.
    p = subprocess.Popen(["docker", "exec", "-i", container, "sh", "-c",
                          'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --unbuffered --batch --skip-column-names "$1"',
                          "sh", database], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, text=True, bufsize=1)
    # 응답 없는 mysql 세션에서 readline이 무기한 막혀 SIGTERM 뒤에도 종료하지 못하는 일을 막기 위해
    # reader 스레드가 줄을 큐에 넣고, 여기서는 타임아웃을 두고 기다린다(lock_loop와 같은 구조).
    lines = queue.Queue()
    def reader():
        for l in p.stdout: lines.put(l)
        lines.put(None)
    threading.Thread(target=reader, daemon=True).start()
    q = (f"SELECT CAST(UNIX_TIMESTAMP(NOW(6))*1000 AS UNSIGNED), IFNULL(SUM(status IN ('ISSUED','SOLD_OUT')),0), "
         f"IFNULL(SUM(status IN ('PENDING','CHECKING')),0), COUNT(*) FROM coupon_application WHERE event_id={int(event_id)};\n")
    f = open(f"{run_dir}/fast-db.csv", "w", newline=""); w = csv.writer(f)
    w.writerow(["host_before_ms", "host_after_ms", "server_ms", "final_visible", "unresolved", "total"])
    try:
        while not stop.is_set():
            t0 = time.time(); b = int(t0 * 1000)
            p.stdin.write(q); p.stdin.flush()
            try:
                line = lines.get(timeout=1.0)
            except queue.Empty:
                continue  # 이 표본은 건너뛴다(값을 만들지 않는다)
            if line is None:
                print("db sampler stopped: mysql session ended", file=sys.stderr); break
            a = int(time.time() * 1000)
            parts = line.strip().split("\t")
            if len(parts) == 4: w.writerow([b, a] + parts)
            f.flush()
            stop.wait(max(0, interval - (time.time() - t0)))
    except (BrokenPipeError, ValueError) as e:
        print(f"db sampler stopped: {type(e).__name__}", file=sys.stderr)
    finally:
        f.close()
        try: p.stdin.close()
        except (BrokenPipeError, ValueError): pass
        p.terminate()

def lock_loop():
    """InnoDB 락 지표 전용 스레드. fast-db.csv 세션과 별도의 mysql 프로세스를 쓰므로, 이 조회가 실패하거나
    (performance_schema 권한·버전 문제 등) 멈춰도 db_loop·prom_loop에 영향이 없다.
    Innodb_row_lock_time은 밀리초 단위 누적값이다(차분은 분석 단계에서)."""
    lock_q = ("SELECT CAST(UNIX_TIMESTAMP(NOW(6))*1000 AS UNSIGNED), "
              "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'), "
              "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_time'), "
              "(SELECT COUNT(*) FROM performance_schema.data_lock_waits);\n")
    f = None; p = None
    try:
        f = open(f"{run_dir}/fast-mysql-locks.csv", "w", newline=""); w = csv.writer(f)
        w.writerow(["host_before_ms", "host_after_ms", "server_ms", "row_lock_waits", "row_lock_time_ms", "data_lock_waits"])
        f.flush()
        p = subprocess.Popen(["docker", "exec", "-i", container, "sh", "-c",
                              'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --unbuffered --batch --skip-column-names "$1"',
                              "sh", database], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, text=True, bufsize=1)
        lines = queue.Queue()
        def reader():
            for l in p.stdout: lines.put(l)
            lines.put(None)  # EOF sentinel
        threading.Thread(target=reader, daemon=True).start()
        while not stop.is_set():
            t0 = time.time(); b = int(t0 * 1000)
            p.stdin.write(lock_q); p.stdin.flush()
            try:
                line = lines.get(timeout=1.0)
            except queue.Empty:
                continue  # 응답이 없으면 이 표본은 건너뛴다(값을 만들지 않는다)
            if line is None:
                print("lock sampler stopped: mysql session ended", file=sys.stderr); break
            a = int(time.time() * 1000)
            parts = line.strip().split("\t")
            if len(parts) == 4: w.writerow([b, a] + parts)
            f.flush()
            stop.wait(max(0, interval - (time.time() - t0)))
    except Exception as e:
        print(f"lock sampler stopped: {type(e).__name__}", file=sys.stderr)
    finally:
        if f is not None: f.close()
        if p is not None:
            try: p.stdin.close()
            except Exception: pass
            p.terminate()

# 데몬 스레드 + 유한 join: 어떤 mysql 세션이 멈춰도 SIGTERM 뒤 프로세스가 무기한 대기하지 않는다.
ts = [threading.Thread(target=fn, daemon=True) for fn in (prom_loop, db_loop, lock_loop)]
[t.start() for t in ts]
while not stop.is_set():
    stop.wait(0.5)
for t in ts:
    t.join(timeout=3)
