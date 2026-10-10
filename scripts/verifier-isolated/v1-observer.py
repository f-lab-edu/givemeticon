#!/usr/bin/env python3
"""V1 회원별 최종 HTTP 관찰기(POST 생성기와 분리된 프로세스). 최초 POST 원본 로그의 회원을 제한된 rps/동시성으로 폴링해
'최초 송신 시각 -> 실제 ISSUED/SOLD_OUT 관찰 시각'을 기록하고, 관측 간격/누락/503/timeout을 전체 최초 요청 분모와 대조한다.
사용: v1-observer.py --post-log <k6-failures.log> --targets http://h1:p,http://h2:p --event-id N --out-dir D
      [--budget-ms 180000] [--grace-ms 0] [--max-rps 200] [--concurrency 32] [--interval-ms 1000] [--timeout-ms 3000]
      [--start-delay-ms 0] --assumed-latency-ms <ms> --assumed-latency-source measured-get-smoke|conservative-assumption [--require-budget] [--record-poll-windows]
원칙:
 - 분모 = POST 로그의 모든 최초 admission 회원. 회원의 '최초 요청'은 유효한 sentAtMs 가 가장 이른 admission 기록(파일 순서는 신뢰하지 않음).
 - 요청 결과(firstRequestResult, 예: event_closed=ENDED)와 이후 application 결과(terminalStatus)는 별도 필드이며 서로를 덮지 않는다.
 - 폴링 필요성은 해당 회원의 admission + duplicate 기록 '전부'가 event_closed 일 때만 면제(business_ended). 하나라도 비ENDED 면 폴링한다
   (ENDED 는 그 요청의 업무 최종값일 뿐 다른 시도의 결과를 해결하지 않는다).
 - sentAtMs 가 없거나 유효하지 않은 admission 기록이 하나라도 있는 회원은 slo_indeterminate: 분모에 남기고 within-budget 성공에서 제외한다.
 - 최종 = HTTP 200 + data.status in (ISSUED, SOLD_OUT). CHECKING/PENDING/GET 의 ENDED 는 비최종. 503/non-200/timeout 은 계수하고 0 으로 대체하지 않는다.
 - 고정 worker pool(=concurrency)이 연결을 재사용하고, timeout/오류/종료 시 연결을 close 한다. 회원당 client in-flight 1개(완료 후 재예약), 실제 발송 직전 deadline 재확인.
 - 실행 전 관찰 예산 점검: 폴링 대상 수/실효 rps 로 1회전 시간이 가장 이른 deadline 까지 남은 시간보다 길면 observation_budget_insufficient 를 명시한다(성공 보장 아님).
 - 시각은 호스트 epoch ms. POST 로그의 sentAtMs 와 같은 호스트/시계여야 한다.
산출: observer-records.jsonl(회원별), observer-summary.json
"""
import argparse, collections, heapq, http.client, json, pathlib, queue, random, re, resource, socket, sys, threading, time, urllib.parse

p = argparse.ArgumentParser()
p.add_argument("--post-log", required=True); p.add_argument("--targets", required=True); p.add_argument("--event-id", required=True, type=int)
p.add_argument("--out-dir", required=True); p.add_argument("--budget-ms", type=int, default=180000); p.add_argument("--grace-ms", type=int, default=0)
p.add_argument("--max-rps", type=float, default=200.0); p.add_argument("--concurrency", type=int, default=32)
p.add_argument("--interval-ms", type=int, default=1000); p.add_argument("--timeout-ms", type=int, default=3000); p.add_argument("--start-delay-ms", type=int, default=0)
p.add_argument("--jitter-ms", type=int, default=0)
p.add_argument("--assumed-latency-ms", type=int, required=True, help="GET 응답 지연 가정(ms, 양수). POST p95 를 대입하지 말 것")
p.add_argument("--assumed-latency-source", required=True, choices=["measured-get-smoke", "conservative-assumption"],
               help="measured-get-smoke: 실제 GET smoke 의 p95 (--assumed-latency-sample-n/--assumed-latency-condition 필요). conservative-assumption: 예) GET timeout 값, provisional 로 표시")
p.add_argument("--assumed-latency-sample-n", type=int, default=0); p.add_argument("--assumed-latency-condition", default="")
p.add_argument("--require-budget", action="store_true"); p.add_argument("--record-poll-windows", action="store_true")
p.add_argument("--calibration", action="store_true", help="GET calibration only: <=40 members, 60s from observation start, no SLO verdict")
a = p.parse_args()
if a.calibration:
    if a.require_budget or a.start_delay_ms or not 1 <= a.concurrency <= 4 or not 0 < a.max_rps <= 20 or not 0 < a.timeout_ms <= 3000:
        p.error("calibration requires no SLO budget gate/delay; concurrency<=4, rps<=20, timeout<=3000ms")
    a.budget_ms = 60000; a.grace_ms = 0
if a.assumed_latency_ms <= 0: p.error("--assumed-latency-ms must be a positive integer")
if a.assumed_latency_source == "measured-get-smoke" and (a.assumed_latency_sample_n <= 0 or not a.assumed_latency_condition):
    p.error("--assumed-latency-source measured-get-smoke requires --assumed-latency-sample-n > 0 and --assumed-latency-condition (smoke 조건 설명)")
targets = [t.strip() for t in a.targets.split(",") if t.strip()]
MSG = re.compile(r'msg="(.*)"\s*$')
now_ms = lambda: int(time.time() * 1000)

def parse(path):
    recs, bad = [], 0
    for raw in pathlib.Path(path).read_text(errors="replace").splitlines():
        line = raw.strip(); m = MSG.search(line)
        payload = m.group(1) if m else (line if line.startswith("{") else None)
        if payload is None: continue
        try: recs.append(json.loads(payload.replace('\\"', '"').replace("\\\\", "\\")))
        except ValueError: bad += 1
    return recs, bad

def valid_ms(v):   # 유효한 epoch ms 만 허용(문자열/음수/0/비현실적 값 제외)
    return isinstance(v, (int, float)) and not isinstance(v, bool) and 1e11 < v < 4e12

recs, parse_errors = parse(a.post_log)
adm = [r for r in recs if r.get("kind") == "admission" and "memberId" in r]
dup_cats = collections.defaultdict(list); dup_recs = collections.defaultdict(list)
for r in recs:
    if r.get("kind") == "duplicate" and "memberId" in r: dup_cats[r["memberId"]].append(r.get("category")); dup_recs[r["memberId"]].append(r)
by_member = collections.defaultdict(list)
for idx, r in enumerate(adm): by_member[r["memberId"]].append((idx, r))

state, order_conflicts, missing_sent = {}, [], 0
for m, lst in by_member.items():
    timed = [(r["sentAtMs"], i, r) for i, r in lst if valid_ms(r.get("sentAtMs"))]
    bad_t = len(lst) - len(timed); missing_sent += bad_t
    chosen = min(timed, key=lambda x: (x[0], x[1]))[2] if timed else lst[0][1]
    if len(lst) > 1 and lst[0][1] is not chosen: order_conflicts.append(m)
    adm_cats = [x.get("category") for _, x in lst]; all_cats = adm_cats + dup_cats.get(m, [])
    # business_ended 를 '180초 수렴'으로 세려면 모든 시도의 ENDED 응답 시각(respondedAtMs)이 유효해야 한다(시각 누락은 성공으로 합치지 않음)
    all_recs = [x for _, x in lst] + dup_recs.get(m, [])
    resp_times = [x.get("respondedAtMs") for x in all_recs]
    ended_confirmed_at = max(resp_times) if resp_times and all(valid_ms(t) for t in resp_times) else None
    polled = not all(c == "event_closed" for c in all_cats)
    state[m] = {"memberId": m, "firstSentAtMs": chosen.get("sentAtMs") if valid_ms(chosen.get("sentAtMs")) else None,
                "firstRequestResult": chosen.get("category"), "admissionCategories": adm_cats, "duplicateCategories": dup_cats.get(m, []),
                "postTarget": chosen.get("target"), "sloIndeterminate": bad_t > 0 or not timed, "invalidOrMissingSentAtRecords": bad_t,
                "polled": polled, "classification": None if polled else "business_ended", "endedConfirmedAtMs": ended_confirmed_at if not polled else None, "polls": 0, "ok_polls": 0,
                "http": collections.Counter(), "errors": collections.Counter(), "lastStatus": None, "firstPollAtMs": None, "lastOkPollAtMs": None,
                "maxGapMs": 0, "terminalStatus": None, "terminalObservedAtMs": None, "firstPollAlreadyTerminal": False, "endedResponses": 0,
                "skippedPastDeadline": 0, "pollWindows": []}

if a.calibration:
    if len(state) > 40: p.error("calibration allows at most 40 recorded members")
    for st in state.values():
        st["polled"] = True; st["classification"] = None; st["calibrationPolls"] = []
TERMINAL = ("ISSUED", "SOLD_OUT")
lock = threading.Lock()
lat_samples = collections.defaultdict(list)
proc_t0 = time.time(); cpu_t0 = resource.getrusage(resource.RUSAGE_SELF)
t_start = now_ms() + a.start_delay_ms
def base_ms(st): return t_start if a.calibration else (st["firstSentAtMs"] if st["firstSentAtMs"] else t_start)    # 시각 불명 회원은 관찰 시작 시각을 기준으로 상한만 둔다
def deadline(st): return base_ms(st) + a.budget_ms + a.grace_ms

# ---- 관찰 예산 점검(실행 전)
polled_members = [st for st in state.values() if st["polled"]]
eff_rps = min(a.max_rps, a.concurrency * 1000.0 / max(1, a.assumed_latency_ms))
rotation_s = len(polled_members) / eff_rps if eff_rps > 0 else float("inf")
tightest = min((deadline(st) for st in polled_members), default=None)
window_s = (tightest - t_start) / 1000.0 if tightest else None
earliest_sent = min((st["firstSentAtMs"] for st in state.values() if st["firstSentAtMs"]), default=None)
budget = {"assumed_latency": {"ms": a.assumed_latency_ms, "source": a.assumed_latency_source, "provisional": a.assumed_latency_source != "measured-get-smoke",
                              "sample_n": a.assumed_latency_sample_n or None, "condition": a.assumed_latency_condition or None,
                              "note": "feasibility estimate only: smoke GET latency does not guarantee latency under load; never substitute POST p95"},
          "polled_members": len(polled_members), "effective_rps_bound": round(eff_rps, 1), "one_rotation_s": round(rotation_s, 1),
          "time_to_tightest_deadline_s": round(window_s, 1) if window_s is not None else None,
          "observer_start_delay_after_first_post_ms": (t_start - earliest_sent) if earliest_sent else None,
          "observation_budget_insufficient": bool(polled_members) and (window_s is None or window_s <= 0 or rotation_s > window_s),
          "note": "insufficient => at least one member cannot get even one poll before its deadline; sufficient does NOT guarantee success"}
out = pathlib.Path(a.out_dir); out.mkdir(parents=True, exist_ok=True)
if a.require_budget and budget["observation_budget_insufficient"]:
    (out / "observer-summary.json").write_text(json.dumps({"not_run": True, "reason": "observation_budget_insufficient", "observation_budget": budget}, indent=2))
    print(json.dumps({"not_run": True, "observation_budget": budget}, indent=2)); sys.exit(4)

heap, rng = [], random.Random(7)
for st in polled_members:
    heapq.heappush(heap, (max(t_start, base_ms(st)) + rng.randint(0, max(1, a.interval_ms)), st["memberId"]))

# ---- 연결/스레드 계측
stats = {"opened": 0, "closed": 0, "live": 0, "max_live": 0, "peak_threads": 0}
slock = threading.Lock()
tls = threading.local()
def get_conns():
    if not hasattr(tls, "c"): tls.c = {}
    return tls.c
def sync_live(conn, was_open):
    is_open = conn.sock is not None
    with slock:
        if is_open and not was_open: stats["opened"] += 1; stats["live"] += 1; stats["max_live"] = max(stats["max_live"], stats["live"])
        elif was_open and not is_open: stats["closed"] += 1; stats["live"] -= 1
def close_conn(conn):
    was = conn.sock is not None
    try: conn.close()
    finally: sync_live(conn, was)
def conn_for(idx):
    c = get_conns()
    if idx not in c:
        u = urllib.parse.urlparse(targets[idx]); c[idx] = http.client.HTTPConnection(u.hostname, u.port, timeout=a.timeout_ms / 1000)
    return c[idx]

def poll(m):
    st = state[m]; idx = ((st["postTarget"] or 1) - 1) % len(targets)
    conn = conn_for(idx); was = conn.sock is not None
    if a.calibration:
        conn.timeout = max(.001, min(a.timeout_ms / 1000, (deadline(st) - now_ms()) / 1000))
        if conn.sock is not None: conn.sock.settimeout(conn.timeout)
    t0 = now_ms(); status = None; body_status = None; err = None
    try:
        conn.request("GET", f"/test-support/coupon-events/{a.event_id}/applications/me", headers={"X-Coupon-Admission-Test-Member": str(m)})
        resp = conn.getresponse(); raw = resp.read(); status = resp.status
        if status == 200:
            try: body_status = (json.loads(raw).get("data") or {}).get("status")
            except ValueError: err = "bad_json"
    except (TimeoutError, socket.timeout): err = "timeout"
    except Exception as e: err = "timeout" if "timed out" in str(e) else "connection_error"
    sync_live(conn, was)                                            # 이번 요청에서 일어난 open/close 전이를 먼저 계상
    if err in ("timeout", "connection_error"): close_conn(conn)    # 오류/timeout 뒤 연결 재사용 금지(CannotSendRequest 방지)
    t1 = now_ms()
    with lock:
        if err is None: lat_samples[str(status)].append(t1 - t0)      # 응답을 받은 요청의 지연(상태별). timeout/연결오류는 지연이 아니라 오류로만 계수
        st["polls"] += 1
        if a.calibration: st["calibrationPolls"].append({"sentAtMs": t0, "respondedAtMs": t1, "latencyMs": t1-t0, "httpStatus": status, "error": err, "applicationStatus": body_status})
        if st["firstPollAtMs"] is None: st["firstPollAtMs"] = t0
        if err: st["errors"][err] += 1
        elif status != 200: st["http"][str(status)] += 1; st["errors"][f"http_{status}"] += 1
        else:
            st["ok_polls"] += 1; st["http"]["200"] += 1; st["lastStatus"] = body_status
            if st["lastOkPollAtMs"] is not None: st["maxGapMs"] = max(st["maxGapMs"], t1 - st["lastOkPollAtMs"])
            st["lastOkPollAtMs"] = t1
            if body_status == "ENDED": st["endedResponses"] += 1
            if body_status in TERMINAL and st["terminalObservedAtMs"] is None:
                st["terminalStatus"] = body_status; st["terminalObservedAtMs"] = t1
                st["firstPollAlreadyTerminal"] = st["ok_polls"] == 1

rate_gap = 1.0 / a.max_rps
next_slot = [time.time()]; slot_lock = threading.Lock()
def wait_slot():
    with slot_lock:
        t = max(time.time(), next_slot[0]); next_slot[0] = t + rate_gap
    d = t - time.time()
    if d > 0: time.sleep(d)

heap_lock = threading.Lock(); inflight = set(); work = queue.Queue(); first_poll_t = [None]; last_poll_t = [0]

def worker():
    try:
        while True:
            m = work.get()
            if m is None: return
            st = state[m]
            try:
                wait_slot()
                if now_ms() > deadline(st):           # 대기 뒤 실제 발송 직전 deadline 재확인 -> 발송하지 않는다
                    with lock: st["skippedPastDeadline"] += 1
                else:
                    if first_poll_t[0] is None: first_poll_t[0] = time.time()
                    sent_ms = now_ms(); poll(m); done_ms = now_ms(); last_poll_t[0] = time.time()
                    if a.record_poll_windows:
                        with lock: st["pollWindows"].append([sent_ms, done_ms])
            finally:
                with heap_lock:
                    inflight.discard(m)
                    jit = rng.randint(-a.jitter_ms, a.jitter_ms) if a.jitter_ms else 0
                    if st["terminalObservedAtMs"] is None and now_ms() + a.interval_ms < deadline(st):
                        heapq.heappush(heap, (now_ms() + max(1, a.interval_ms + jit), m))     # 완료 후 재예약 -> 회원당 in-flight 1개
    finally:
        for conn in list(get_conns().values()): close_conn(conn)                              # 종료 시 모든 연결 close

pool = [threading.Thread(target=worker, daemon=True) for _ in range(a.concurrency)]
for t in pool: t.start()
while True:
    with heap_lock:
        pending = bool(heap) or bool(inflight)
        due = None; n = now_ms()
        if heap and heap[0][0] <= n:
            _, m = heapq.heappop(heap); st = state[m]
            if st["terminalObservedAtMs"] is None and n <= deadline(st) and m not in inflight:
                inflight.add(m); due = m
    stats["peak_threads"] = max(stats["peak_threads"], threading.active_count())
    if due is not None: work.put(due)
    elif not pending: break
    else: time.sleep(0.005)
for _ in pool: work.put(None)
for t in pool: t.join()

def classify(st):
    if a.calibration: return "calibration_only"
    if st["classification"]: return st["classification"]
    if st["sloIndeterminate"]: return "slo_indeterminate"
    if st["terminalObservedAtMs"] is not None:
        return "terminal_within_budget" if st["terminalObservedAtMs"] - st["firstSentAtMs"] <= a.budget_ms else "terminal_late_after_budget"
    if st["ok_polls"] == 0 and st["polls"] > 0: return "unreachable_all_polls_failed"
    if st["polls"] == 0: return "never_polled"
    return "never_terminal_observed"
for st in state.values(): st["classification"] = classify(st)
with open(out / "observer-records.jsonl", "w") as f:
    for st in state.values():
        r = dict(st); r["http"] = dict(st["http"]); r["errors"] = dict(st["errors"])
        r["applicationResult"] = st["terminalStatus"]          # 이후 application 결과(요청 결과 firstRequestResult 와 별개)
        if not a.record_poll_windows: r.pop("pollWindows", None)
        r["firstPostToTerminalObservedMs"] = ((st["terminalObservedAtMs"] - st["firstSentAtMs"]) if st["terminalObservedAtMs"] and st["firstSentAtMs"] else None) if not a.calibration else None
        f.write(json.dumps(r, ensure_ascii=False) + "\n")

def pct(v, q):
    if not v: return None
    s = sorted(v); return s[min(len(s) - 1, int(round(q / 100 * (len(s) - 1))))]
cls = collections.Counter(s["classification"] for s in state.values())
lat = [s["terminalObservedAtMs"] - s["firstSentAtMs"] for s in state.values() if s["terminalObservedAtMs"] and s["firstSentAtMs"] and not s["sloIndeterminate"]]
gaps = [s["maxGapMs"] for s in state.values() if s["ok_polls"] > 1]
errs = collections.Counter(); httpc = collections.Counter()
for s in state.values(): errs.update(s["errors"]); httpc.update(s["http"])
total_polls = sum(s["polls"] for s in state.values())
span = (last_poll_t[0] - first_poll_t[0]) if first_poll_t[0] else None
first_ended_dup_nonended = sum(1 for s in state.values() if s["firstRequestResult"] == "event_closed" and any(c != "event_closed" for c in s["duplicateCategories"] + s["admissionCategories"]))
achieved = (total_polls / span) if span and span > 0 else None
polled_n = len(polled_members)
rotation_post_s = (polled_n / achieved) if achieved else None
never_polled_n = sum(1 for st in polled_members if st["polls"] == 0)
skipped_total = sum(st["skippedPastDeadline"] for st in state.values())
# 직접 증거 기반: 한 번도 못 폴링한 회원 / deadline 때문에 건너뛴 폴링 / 끝내 미확정인데 관측 간격이 budget 보다 길었던 회원
unfinished_gap = sum(1 for st in polled_members if st["terminalObservedAtMs"] is None and st["maxGapMs"] > a.budget_ms)
post_insufficient = bool(polled_n) and (never_polled_n > 0 or skipped_total > 0 or unfinished_gap > 0)
budget_post = {"achieved_rps_average_over_poll_span": round(achieved, 1) if achieved else None,
               "one_rotation_s_at_average_rps": round(rotation_post_s, 1) if rotation_post_s else None,
               "average_rps_caveat": "average includes periods with less work after members converged; it alone does NOT invalidate terminals already observed within budget",
               "per_member_budget_s": a.budget_ms / 1000.0, "members_never_polled": never_polled_n, "polls_skipped_past_deadline": skipped_total,
               "unfinished_members_with_gap_over_budget": unfinished_gap,
               "post_run_observation_budget_insufficient": post_insufficient,
               "differs_from_pre_run_verdict": post_insufficient != budget["observation_budget_insufficient"],
               "overrides_pre_run_verdict": post_insufficient and not budget["observation_budget_insufficient"],
               "authoritative_verdict": "insufficient" if (post_insufficient or budget["observation_budget_insufficient"]) else "sufficient_by_both_checks(not a success guarantee)",
               "note": "post-run verdict (direct evidence) takes precedence over the pre-run estimate; observed terminals stay counted regardless"}
n_den = max(1, len(state))
ended_all = [st for st in state.values() if st["classification"] == "business_ended"]
ended_timed_in = [st for st in ended_all if st["endedConfirmedAtMs"] and st["firstSentAtMs"] and not st["sloIndeterminate"]
                  and st["endedConfirmedAtMs"] - st["firstSentAtMs"] <= a.budget_ms]
ended_untimed = [st for st in ended_all if not st["endedConfirmedAtMs"] or not st["firstSentAtMs"] or st["sloIndeterminate"]]
_ids_in = {id(x) for x in ended_timed_in}; _ids_un = {id(x) for x in ended_untimed}
ended_late = [st for st in ended_all if id(st) not in _ids_in and id(st) not in _ids_un]
adr001 = {"issuance_result_coverage": {"definition": "ISSUED/SOLD_OUT observed via GET within budget", "count": cls["terminal_within_budget"], "of": len(state),
                                       "rate": round(cls["terminal_within_budget"] / n_den, 4)},
          "business_outcome_convergence": {"definition": "issuance_result_coverage numerator + business_ended whose ENDED response times (all attempts) are valid and within budget",
              "count": cls["terminal_within_budget"] + len(ended_timed_in), "of": len(state),
              "rate": round((cls["terminal_within_budget"] + len(ended_timed_in)) / n_den, 4),
              "business_ended_counted": len(ended_timed_in), "business_ended_untimed_not_counted": len(ended_untimed), "business_ended_late_not_counted": len(ended_late),
              "note": "uncertain earlier attempts, slo_indeterminate, untimed ENDED and late ENDED are never merged into success"}}
ru = resource.getrusage(resource.RUSAGE_SELF); wall = max(1e-9, time.time() - proc_t0)
cpu_s = (ru.ru_utime - cpu_t0.ru_utime) + (ru.ru_stime - cpu_t0.ru_stime)
maxrss_mib = ru.ru_maxrss / (1024.0 * 1024.0 if sys.platform == "darwin" else 1024.0)     # macOS: bytes, Linux: KiB
def dist_ms(v): return {"n": len(v), "p50": pct(v, 50), "p95": pct(v, 95), "p99": pct(v, 99), "max": max(v) if v else None}
get_latency = {"by_http_status": {k: dist_ms(v) for k, v in sorted(lat_samples.items())}, "http_200": dist_ms(lat_samples.get("200", [])),
               "timeouts_or_connection_errors_not_in_latency": errs.get("timeout", 0) + errs.get("connection_error", 0),
               "note": "feeds --assumed-latency-ms (measured-get-smoke). State the load condition (idle vs under N rps POST load) separately; a low smoke p95 does not guarantee latency under the required load"}
observer_process = {"wall_s": round(wall, 2), "cpu_s": round(cpu_s, 3), "avg_cpu_fraction": round(cpu_s / wall, 3), "max_rss_mib": round(maxrss_mib, 1),
                    "note": "this process only; docker CLI/sampler/Hikari processes must be added to the shared observer budget separately"}
summary = {
    "input": {"post_log_parse_errors": parse_errors, "admission_records": len(adm), "unique_first_post_members": len(state),
              "members_with_multiple_admission_records": sum(1 for l in by_member.values() if len(l) > 1),
              "members_where_file_order_first_differs_from_earliest_sentAtMs": len(order_conflicts), "order_conflict_examples": order_conflicts[:10],
              "admission_records_missing_or_invalid_sentAtMs": missing_sent,
              "members_with_conflicting_categories": sum(1 for s_ in state.values() if len(set(s_["admissionCategories"] + s_["duplicateCategories"])) > 1),
              "members_first_request_ended_but_other_attempt_not_ended(polled)": first_ended_dup_nonended},
    "observation_budget": budget, "observation_budget_post_run": budget_post, "adr001_metrics": adr001,
    "denominator_first_posts": len(state), "classification": dict(cls),
    "slo": {"success_terminal_within_budget": cls["terminal_within_budget"], "denominator": len(state), "budget_ms": a.budget_ms,
            "success_rate_of_full_denominator": round(cls["terminal_within_budget"] / max(1, len(state)), 4),
            "not_counted_as_success": {k: cls[k] for k in ("business_ended", "slo_indeterminate", "terminal_late_after_budget", "never_terminal_observed",
                                                           "never_polled", "unreachable_all_polls_failed")},
            "note": "business_ended is a separate business outcome of the unaccepted request; it is not an ISSUED/SOLD_OUT success and never resolves another attempt"},
    "polls_skipped_past_deadline": sum(s_["skippedPastDeadline"] for s_ in state.values()),
    "first_post_to_terminal_observed_ms(excluding slo_indeterminate)": {"n": len(lat), "p50": pct(lat, 50), "p95": pct(lat, 95), "p99": pct(lat, 99), "max": max(lat) if lat else None},
    "first_poll_already_terminal(lower bound unknown)": sum(1 for s in state.values() if s["firstPollAlreadyTerminal"]),
    "observation_gap_ms_per_member_max": {"p50": pct(gaps, 50), "p95": pct(gaps, 95), "max": max(gaps) if gaps else None, "configured_interval_ms": a.interval_ms},
    "polls": {"total": total_polls, "http_status": dict(httpc), "errors": dict(errs), "timeouts": errs.get("timeout", 0),
              "achieved_rps": round(total_polls / span, 1) if span and span > 0 else None, "configured_max_rps": a.max_rps, "concurrency": a.concurrency},
    "get_latency_ms": get_latency, "observer_process": observer_process,
    "resources": {"pool_threads": a.concurrency, "peak_active_threads_in_process": stats["peak_threads"], "connections_opened": stats["opened"],
                  "connections_closed": stats["closed"], "max_open_connections": stats["max_live"], "open_connections_at_exit": stats["live"],
                  "note": "client in-flight per member is 1; a request abandoned by a client timeout may still run on the server"},
    "get_ended_responses(not final)": sum(s["endedResponses"] for s in state.values()),
    "notes": ["observed time resolution is limited by achieved interval; terminal time is an UPPER bound on commit-visible time",
              "classification counts every first POST member; none is dropped from the denominator",
              "DB reconciliation is separate and does not replace this client observation"],
}
if a.calibration:
    for key in ("adr001_metrics", "slo", "classification", "first_post_to_terminal_observed_ms(excluding slo_indeterminate)", "observation_budget_post_run"):
        summary.pop(key, None)
    summary["slo"] = {"verdict": "NOT_APPLICABLE(calibration)"}
    summary["observation_budget"]["verdict"] = "NOT_APPLICABLE(calibration)"
    summary["calibration"] = {"started_ms": t_start, "deadline_ms": t_start+60000, "max_members": 40, "note": "original POST timestamps preserved; no issuance coverage or convergence verdict"}
    summary["get_latency_ms"]["measurement"] = "end-to-end client observed latency, includes client scheduling"
(out / "observer-summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
print(json.dumps(summary, indent=2, ensure_ascii=False))
