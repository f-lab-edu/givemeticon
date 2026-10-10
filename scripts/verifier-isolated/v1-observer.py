#!/usr/bin/env python3
"""V1 회원별 최종 HTTP 관찰기(POST 생성기와 분리된 프로세스). 최초 POST 원본 로그의 회원을 제한된 rps/동시성으로 폴링해
'최초 송신 시각 -> 실제 ISSUED/SOLD_OUT 관찰 시각'을 기록하고, 관측 간격/누락/503/timeout 을 전체 최초 요청 분모와 대조한다.
사용: v1-observer.py --post-log <k6-failures.log> --targets http://h1:p,http://h2:p --event-id N --out-dir D
      [--budget-ms 180000] [--grace-ms 0] [--max-rps 200] [--concurrency 32] [--interval-ms 1000] [--timeout-ms 3000] [--start-delay-ms 0]
원칙:
 - 분모 = POST 로그의 모든 최초 요청 회원. 회원의 '최초 요청'은 sentAtMs 가 가장 이른 기록(파일 순서 아님; 불일치/누락은 보고).\n   회원당 폴링은 동시에 1개만(완료 후 재예약), 실제 발송 직전 deadline 재확인. 로그의 ENDED(event_closed) 응답 회원은 폴링하지 않는다:
   ENDED 는 그 미접수 요청의 업무 최종값일 뿐 이전 uncertain 시도를 해결하지 않는다(여기서는 해당 회원에 이전 시도가 없으므로 'business_ended').
 - 폴링 대상: success/checking/client_timeout/connection_error/http_error 등 '접수 여부가 확정/불확실'한 회원. GET 의 ENDED 응답은 최종 아님(ended_response 로 기록).
 - 최종 = HTTP 200 + data.status in (ISSUED, SOLD_OUT). CHECKING/PENDING 은 비최종. 503/기타 non-200/timeout 은 오류로 계수, 0 으로 대체하지 않는다.
 - 관찰기가 POST 생성기를 방해하지 않도록 전체 rps 상한(token-bucket)과 동시성 상한을 둔다. 달성한 폴링 rps/관측 간격을 그대로 보고한다(해상도 한계).
 - 시각은 호스트 epoch ms. POST 로그의 sentAtMs 와 같은 호스트/시계여야 한다(다르면 결과를 신뢰하지 않는다).
산출: observer-records.jsonl(회원별), observer-summary.json
"""
import argparse, collections, heapq, http.client, json, pathlib, random, re, sys, threading, time, urllib.parse

p = argparse.ArgumentParser()
p.add_argument("--post-log", required=True); p.add_argument("--targets", required=True); p.add_argument("--event-id", required=True, type=int)
p.add_argument("--out-dir", required=True); p.add_argument("--budget-ms", type=int, default=180000); p.add_argument("--grace-ms", type=int, default=0)
p.add_argument("--max-rps", type=float, default=200.0); p.add_argument("--concurrency", type=int, default=32)
p.add_argument("--interval-ms", type=int, default=1000); p.add_argument("--timeout-ms", type=int, default=3000); p.add_argument("--start-delay-ms", type=int, default=0)
p.add_argument("--jitter-ms", type=int, default=0); p.add_argument("--record-poll-windows", action="store_true")
a = p.parse_args()
targets = [t.strip() for t in a.targets.split(",") if t.strip()]
MSG = re.compile(r'msg="(.*)"\s*$')

def parse(path):
    recs, bad = [], 0
    for raw in pathlib.Path(path).read_text(errors="replace").splitlines():
        line = raw.strip(); m = MSG.search(line)
        payload = m.group(1) if m else (line if line.startswith("{") else None)
        if payload is None: continue
        try: recs.append(json.loads(payload.replace('\\"', '"').replace("\\\\", "\\")))
        except ValueError: bad += 1
    return recs, bad

recs, parse_errors = parse(a.post_log)
adm = [r for r in recs if r.get("kind") == "admission"]
dup_posted = {r["memberId"] for r in recs if r.get("kind") == "duplicate"}
by_member = collections.defaultdict(list)
for idx, r in enumerate(adm): by_member[r["memberId"]].append((idx, r))
first, multi = {}, collections.Counter()
order_conflicts, missing_sent = [], 0
for m, lst in by_member.items():
    multi[m] = len(lst)
    with_t = [(r["sentAtMs"], i, r) for i, r in lst if r.get("sentAtMs")]
    missing_sent += len(lst) - len(with_t)
    # '최초 요청' = sentAtMs 가 가장 이른 기록(로그 파일 순서는 응답 완료 순서일 수 있어 신뢰하지 않는다). sentAtMs 가 전혀 없으면 파일 순서 + 표시.
    chosen = min(with_t)[2] if with_t else lst[0][1]
    first[m] = chosen
    if len(lst) > 1 and lst[0][1] is not chosen: order_conflicts.append(m)

state = {}   # memberId -> dict
for m, r in first.items():
    cats = [x.get("category") for _, x in by_member[m]]
    st = {"memberId": m, "firstSentAtMs": r.get("sentAtMs"), "postCategory": r.get("category"), "postCategories": cats, "postTarget": r.get("target"), "polls": 0, "ok_polls": 0,
          "http": collections.Counter(), "errors": collections.Counter(), "lastStatus": None, "firstPollAtMs": None, "lastOkPollAtMs": None,
          "maxGapMs": 0, "terminalStatus": None, "terminalObservedAtMs": None, "firstPollAlreadyTerminal": False, "endedResponses": 0, "duplicatePosted": m in dup_posted,
          "skippedPastDeadline": 0, "pollWindows": []}
    # ENDED 는 그 미접수 요청의 최종값일 뿐 이전(또는 다른) uncertain 시도를 해결하지 않는다 -> 모든 기록이 event_closed 일 때만 폴링 제외
    st["polled"] = not all(c == "event_closed" for c in cats)
    st["classification"] = "business_ended" if not st["polled"] else None
    state[m] = st

TERMINAL = ("ISSUED", "SOLD_OUT")
lock = threading.Lock()
now_ms = lambda: int(time.time() * 1000)
t_start = now_ms() + a.start_delay_ms
heap = []   # (due_ms, memberId)
rng = random.Random(7)
for m, st in state.items():
    if st["polled"] and st["firstSentAtMs"]:
        heapq.heappush(heap, (max(t_start, st["firstSentAtMs"]) + rng.randint(0, max(1, a.interval_ms)), m))
    elif st["polled"]: st["classification"] = "no_sent_time_in_log"

tls = threading.local()
def conn(idx):
    c = getattr(tls, "c", None)
    if c is None: tls.c = {}
    if idx not in tls.c:
        u = urllib.parse.urlparse(targets[idx]); tls.c[idx] = http.client.HTTPConnection(u.hostname, u.port, timeout=a.timeout_ms / 1000)
    return tls.c[idx]

def poll(m):
    st = state[m]; idx = ((st["postTarget"] or 1) - 1) % len(targets)
    t0 = now_ms(); status = None; body_status = None; err = None
    try:
        c = conn(idx); c.request("GET", f"/test-support/coupon-events/{a.event_id}/applications/me", headers={"X-Coupon-Admission-Test-Member": str(m)})
        resp = c.getresponse(); raw = resp.read(); status = resp.status
        if status == 200:
            try: body_status = (json.loads(raw).get("data") or {}).get("status")
            except ValueError: err = "bad_json"
    except TimeoutError: err = "timeout"
    except Exception as e:
        err = "timeout" if "timed out" in str(e) else "connection_error"
        try: tls.c.pop(idx, None)
        except Exception: pass
    t1 = now_ms()
    with lock:
        st["polls"] += 1
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
    return st

rate_gap = 1.0 / a.max_rps
next_slot = [time.time()]; slot_lock = threading.Lock()
def wait_slot():
    with slot_lock:
        t = max(time.time(), next_slot[0]); next_slot[0] = t + rate_gap
    d = t - time.time()
    if d > 0: time.sleep(d)

sem = threading.Semaphore(a.concurrency); heap_lock = threading.Lock(); first_poll_t = [None]; last_poll_t = [0]; inflight = set()
def deadline(st): return st["firstSentAtMs"] + a.budget_ms + a.grace_ms

def worker(m):
    st = state[m]
    try:
        wait_slot()
        if now_ms() > deadline(st):          # semaphore/rate 대기 뒤 실제 발송 직전 deadline 재확인 -> 발송하지 않는다
            st["skippedPastDeadline"] += 1; return
        if first_poll_t[0] is None: first_poll_t[0] = time.time()
        sent_ms = now_ms(); poll(m); done_ms = now_ms(); last_poll_t[0] = time.time()
        if a.record_poll_windows: st["pollWindows"].append([sent_ms, done_ms])
    finally:
        with heap_lock:
            inflight.discard(m)
            jit = rng.randint(-a.jitter_ms, a.jitter_ms) if a.jitter_ms else 0
            if st["terminalObservedAtMs"] is None and now_ms() + a.interval_ms < deadline(st):
                heapq.heappush(heap, (now_ms() + max(1, a.interval_ms + jit), m))   # 완료 후에 재예약 -> 회원당 in-flight 1개
        sem.release()

workers = []
while True:
    with heap_lock: pending = bool(heap) or bool(inflight)
    workers = [w for w in workers if w.is_alive()]
    if not pending and not workers: break
    n = now_ms(); due = None
    with heap_lock:
        if heap and heap[0][0] <= n:
            _, m = heapq.heappop(heap)
            st = state[m]
            if st["terminalObservedAtMs"] is None and n <= deadline(st) and m not in inflight:
                inflight.add(m); due = m
    if due is not None:
        sem.acquire(); w = threading.Thread(target=worker, args=(due,)); w.start(); workers.append(w)
    else:
        time.sleep(0.005)
for w in workers: w.join()

out = pathlib.Path(a.out_dir); out.mkdir(parents=True, exist_ok=True)
def classify(st):
    if st["classification"]: return st["classification"]
    if st["terminalObservedAtMs"] is not None:
        return "terminal_within_budget" if st["terminalObservedAtMs"] - st["firstSentAtMs"] <= a.budget_ms else "terminal_late_after_budget"
    if st["ok_polls"] == 0 and st["polls"] > 0: return "unreachable_all_polls_failed"
    if st["polls"] == 0: return "never_polled"
    return "never_terminal_observed"
for st in state.values(): st["classification"] = classify(st)
with open(out / "observer-records.jsonl", "w") as f:
    for st in state.values():
        r = dict(st); r["http"] = dict(st["http"]); r["errors"] = dict(st["errors"])
        if not a.record_poll_windows: r.pop("pollWindows", None)
        r["firstPostToTerminalObservedMs"] = (st["terminalObservedAtMs"] - st["firstSentAtMs"]) if st["terminalObservedAtMs"] and st["firstSentAtMs"] else None
        f.write(json.dumps(r, ensure_ascii=False) + "\n")

def pct(v, q):
    if not v: return None
    s = sorted(v); return s[min(len(s) - 1, int(round(q / 100 * (len(s) - 1))))]
cls = collections.Counter(s["classification"] for s in state.values())
lat = [s["terminalObservedAtMs"] - s["firstSentAtMs"] for s in state.values() if s["terminalObservedAtMs"] and s["firstSentAtMs"]]
gaps = [s["maxGapMs"] for s in state.values() if s["ok_polls"] > 1]
errs = collections.Counter(); httpc = collections.Counter()
for s in state.values(): errs.update(s["errors"]); httpc.update(s["http"])
total_polls = sum(s["polls"] for s in state.values())
span = (last_poll_t[0] - first_poll_t[0]) if first_poll_t[0] else None
summary = {
    "input": {"post_log_parse_errors": parse_errors, "admission_records": len(adm), "unique_first_post_members": len(first),
              "members_with_multiple_admission_records": sum(1 for c in multi.values() if c > 1),
              "members_where_file_order_first_differs_from_earliest_sentAtMs": len(order_conflicts), "order_conflict_examples": order_conflicts[:10],
              "admission_records_missing_sentAtMs": missing_sent,
              "members_with_conflicting_categories": sum(1 for s_ in state.values() if len(set(s_["postCategories"])) > 1)},
    "polls_skipped_past_deadline": sum(s_["skippedPastDeadline"] for s_ in state.values()),
    "denominator_first_posts": len(first), "classification": dict(cls),
    "terminal_within_budget": {"count": cls["terminal_within_budget"], "of_denominator": len(first), "budget_ms": a.budget_ms,
        "of_polled_members": cls["terminal_within_budget"] / max(1, sum(1 for s in state.values() if s["polled"]))},
    "business_ended_not_polled": cls["business_ended"],
    "never_observed_terminal": cls["never_terminal_observed"] + cls["never_polled"] + cls["unreachable_all_polls_failed"] + cls["no_sent_time_in_log"],
    "first_post_to_terminal_observed_ms": {"n": len(lat), "p50": pct(lat, 50), "p95": pct(lat, 95), "p99": pct(lat, 99), "max": max(lat) if lat else None},
    "first_poll_already_terminal(lower bound unknown)": sum(1 for s in state.values() if s["firstPollAlreadyTerminal"]),
    "observation_gap_ms_per_member_max": {"p50": pct(gaps, 50), "p95": pct(gaps, 95), "max": max(gaps) if gaps else None, "configured_interval_ms": a.interval_ms},
    "polls": {"total": total_polls, "http_status": dict(httpc), "errors": dict(errs), "achieved_rps": round(total_polls / span, 1) if span and span > 0 else None,
              "configured_max_rps": a.max_rps, "concurrency": a.concurrency},
    "get_ended_responses(not final)": sum(s["endedResponses"] for s in state.values()),
    "notes": ["ENDED POST response is final only for that unaccepted request; it never resolves an earlier uncertain attempt",
              "observed time resolution is limited by achieved interval; terminal time is an UPPER bound on commit-visible time",
              "classification counts every first POST member; none is dropped from the denominator"],
}
(out / "observer-summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
print(json.dumps(summary, indent=2, ensure_ascii=False))
