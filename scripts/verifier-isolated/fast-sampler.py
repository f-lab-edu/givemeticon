#!/usr/bin/env python3
"""고빈도 관찰기(기존 monitor-admission.sh는 docker exec 반복으로 표본 간격이 3~8초였다).
사용: fast-sampler.py <run_dir> <mysql_container> <database> <event_id> <port_a> <port_b> [interval_s=0.2]
  - prom 표본: 두 앱 /actuator/prometheus 에서 Hikari active/idle/max/pending, acquire count/sum/max 를 fast-prom.csv 로 기록
  - DB 관찰: 상주 mysql 세션으로 커밋된 상태를 조회(별도 연결, 자동 commit)하여 fast-db.csv 로 기록
      host_before_ms,host_after_ms,server_ms,final_visible,unresolved,total   (server_ms = DB 서버 NOW(6))
    server_ms - (before+after)/2 가 시계 offset 추정치(오차 ±(after-before)/2)다.
  - 쿠폰 지표: 두 앱의 coupon_admission_*, coupon_issuance_* (히스토그램 _bucket 제외) 전 시리즈를 fast-coupon-prom.csv 로 기록
      host_ms,app,series,value   (series = 지표명{태그}, 예: caller/outcome 태그별 select_for_update _count/_sum/_max)
  - MySQL 락: 같은 상주 세션에서 Innodb_row_lock_waits/Innodb_row_lock_time(ms)/performance_schema.data_lock_waits 건수를
    fast-mysql-locks.csv 로 기록   (performance_schema 가 켜져 있어야 한다)
SIGTERM 으로 종료한다. 비밀번호는 컨테이너 env 에서 읽어 인자/로그에 노출하지 않는다.
"""
import csv, re, signal, subprocess, sys, threading, time, urllib.request

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
    q = (f"SELECT CAST(UNIX_TIMESTAMP(NOW(6))*1000 AS UNSIGNED), IFNULL(SUM(status IN ('ISSUED','SOLD_OUT')),0), "
         f"IFNULL(SUM(status IN ('PENDING','CHECKING')),0), COUNT(*) FROM coupon_application WHERE event_id={int(event_id)};\n")
    lock_q = ("SELECT CAST(UNIX_TIMESTAMP(NOW(6))*1000 AS UNSIGNED), "
              "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'), "
              "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_time'), "
              "(SELECT COUNT(*) FROM performance_schema.data_lock_waits);\n")
    f = open(f"{run_dir}/fast-db.csv", "w", newline=""); w = csv.writer(f)
    lf = open(f"{run_dir}/fast-mysql-locks.csv", "w", newline=""); lw = csv.writer(lf)
    lw.writerow(["host_before_ms", "host_after_ms", "server_ms", "row_lock_waits", "row_lock_time_ms", "data_lock_waits"])
    w.writerow(["host_before_ms", "host_after_ms", "server_ms", "final_visible", "unresolved", "total"])
    while not stop.is_set():
        t0 = time.time(); b = int(t0 * 1000)
        p.stdin.write(q); p.stdin.flush()
        line = p.stdout.readline()
        a = int(time.time() * 1000)
        parts = line.strip().split("\t")
        if len(parts) == 4: w.writerow([b, a] + parts)
        f.flush()
        # 락 지표는 별도 문장이지만 같은 세션·같은 주기다. 쿼리가 실패하면(performance_schema 비활성 등)
        # mysql 세션이 끝나므로 EOF에서 루프를 멈춘다 - 이때까지의 기록은 보존된다.
        lb = int(time.time() * 1000)
        p.stdin.write(lock_q); p.stdin.flush()
        lline = p.stdout.readline()
        la = int(time.time() * 1000)
        if not lline: break
        lparts = lline.strip().split("\t")
        if len(lparts) == 4: lw.writerow([lb, la] + lparts)
        lf.flush()
        stop.wait(max(0, interval - (time.time() - t0)))
    f.close(); lf.close(); p.stdin.close(); p.terminate()

ts = [threading.Thread(target=prom_loop), threading.Thread(target=db_loop)]
[t.start() for t in ts]; [t.join() for t in ts]
