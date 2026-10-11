#!/usr/bin/env python3
"""고빈도 관찰기(기존 monitor-admission.sh는 docker exec 반복으로 표본 간격이 3~8초였다).
사용: fast-sampler.py <run_dir> <mysql_container> <database> <event_id> <port_a> <port_b> [interval_s=0.2]
  - prom 표본: 두 앱 /actuator/prometheus 에서 Hikari active/idle/max/pending, acquire count/sum/max 를 fast-prom.csv 로 기록
  - DB 관찰: 상주 mysql 세션으로 커밋된 상태를 조회(별도 연결, 자동 commit)하여 fast-db.csv 로 기록
      host_before_ms,host_after_ms,server_ms,final_visible,unresolved,total   (server_ms = DB 서버 NOW(6))
    server_ms - (before+after)/2 가 시계 offset 추정치(오차 ±(after-before)/2)다.
event_id=- 는 DB 연결 없이 prom만 수집한다. PROM_TIMEOUT_S로 timeout을 정하고 fast-prom-health.json에 실패/공백을 남긴다.
SIGTERM 으로 종료한다. 비밀번호는 컨테이너 env 에서 읽어 인자/로그에 노출하지 않는다.
"""
import csv, json, os, re, signal, subprocess, sys, threading, time, urllib.request

run_dir, container, database, event_id, pa, pb = sys.argv[1:7]
interval = float(sys.argv[7]) if len(sys.argv) > 7 else 0.2
scrape_timeout = float(os.environ.get("PROM_TIMEOUT_S", "1"))
stop = threading.Event()
signal.signal(signal.SIGTERM, lambda *a: stop.set()); signal.signal(signal.SIGINT, lambda *a: stop.set())
M = {k: re.compile(r'^' + k + r'(\{[^}]*\})? ([0-9.eE+-]+)$') for k in (
    'hikaricp_connections_active', 'hikaricp_connections_idle', 'hikaricp_connections_max', 'hikaricp_connections_pending',
    'hikaricp_connections_acquire_seconds_count', 'hikaricp_connections_acquire_seconds_sum', 'hikaricp_connections_acquire_seconds_max',
    'hikaricp_connections_timeout_total')}

def prom_loop():
    f = open(f"{run_dir}/fast-prom.csv", "w", newline=""); w = csv.writer(f)
    w.writerow(["host_ms", "app", "active", "idle", "max", "pending", "acq_count", "acq_sum_s", "acq_max_s", "timeout_total", "scrape_ms"])
    health = {n: {"failures": 0, "successes": 0, "max_success_gap_ms": None, "last_success_ms": None} for n in ("app1", "app2")}
    while not stop.is_set():
        t0 = time.time()
        for name, port in (("app1", pa), ("app2", pb)):
            s = time.time()
            try:
                body = urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/prometheus", timeout=scrape_timeout).read().decode()
            except Exception:
                health[name]["failures"] += 1
                w.writerow([int(s * 1000), name] + ["ERR"] * 8 + [int((time.time()-s)*1000)]); continue
            h = health[name]; now = int(s*1000)
            if h["last_success_ms"] is not None:
                h["max_success_gap_ms"] = max(h["max_success_gap_ms"] or 0, now-h["last_success_ms"])
            h["last_success_ms"] = now; h["successes"] += 1
            v = {}
            for line in body.splitlines():
                for k, rx in M.items():
                    m = rx.match(line)
                    if m: v[k] = v.get(k, 0.0) + float(m.group(2)) if k != 'hikaricp_connections_acquire_seconds_max' else max(v.get(k, 0.0), float(m.group(2)))
            w.writerow([int(s * 1000), name] + [v.get(k, "NA") for k in M] + [int((time.time() - s) * 1000)])
        f.flush()
        stop.wait(max(0, interval - (time.time() - t0)))
    f.close()
    for h in health.values():
        h["trailing_gap_ms"] = int(time.time()*1000)-h["last_success_ms"] if h["last_success_ms"] is not None else None
    with open(f"{run_dir}/fast-prom-health.json", "w") as out:
        json.dump({"interval_s": interval, "timeout_s": scrape_timeout, "apps": health, "scope": "observed samples only; scrape gaps can miss saturation peaks"}, out, indent=2)

def db_loop():
    # Resolve the password only inside the container, never in host argv/stdout.
    p = subprocess.Popen(["docker", "exec", "-i", container, "sh", "-c",
                          'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --unbuffered --batch --skip-column-names "$1"',
                          "sh", database], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, text=True, bufsize=1)
    q = (f"SELECT CAST(UNIX_TIMESTAMP(NOW(6))*1000 AS UNSIGNED), IFNULL(SUM(status IN ('ISSUED','SOLD_OUT')),0), "
         f"IFNULL(SUM(status IN ('PENDING','CHECKING')),0), COUNT(*) FROM coupon_application WHERE event_id={int(event_id)};\n")
    f = open(f"{run_dir}/fast-db.csv", "w", newline=""); w = csv.writer(f)
    w.writerow(["host_before_ms", "host_after_ms", "server_ms", "final_visible", "unresolved", "total"])
    while not stop.is_set():
        t0 = time.time(); b = int(t0 * 1000)
        p.stdin.write(q); p.stdin.flush()
        line = p.stdout.readline()
        a = int(time.time() * 1000)
        parts = line.strip().split("\t")
        if len(parts) == 4: w.writerow([b, a] + parts)
        f.flush()
        stop.wait(max(0, interval - (time.time() - t0)))
    f.close(); p.stdin.close(); p.terminate()

ts = [threading.Thread(target=prom_loop)]
if event_id != "-": ts.append(threading.Thread(target=db_loop))
[t.start() for t in ts]; [t.join() for t in ts]
