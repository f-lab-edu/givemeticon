#!/usr/bin/env python3
"""v1-observer.py 대체 실행 테스트: 합성 HTTP 서버(로컬 앱/DB/컨테이너 없음)로 terminal/late/never/error/timeout/ENDED/중복 회원을 검증한다."""
import collections, http.server, json, pathlib, subprocess, sys, tempfile, threading, time, unittest
HERE = pathlib.Path(__file__).parent
BASE = 700200000
T0 = [0.0]
polls = collections.Counter()
inflight = collections.Counter(); max_inflight = collections.Counter(); ilock = threading.Lock()
def behavior(m):   # offset -> (kind, param)
    return {0: ("immediate", None), 1: ("after", 0.6), 2: ("after", 2.6), 3: ("never", None), 4: ("always503", None), 5: ("timeout", None),
            6: ("flaky", 2), 7: ("ended_get", None), 8: ("immediate", None), 10: ("never", None), 11: ("slow", 0.4), 12: ("timeout_once", 0.5), 13: ("immediate", None), 14: ("immediate", None)}.get(m - BASE, ("never", None) if 30 <= m - BASE < 60 else ("slow", 0.3) if 60 <= m - BASE < 90 else ("immediate", None))
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_GET(self):
        m = int(self.headers["X-Coupon-Admission-Test-Member"]); polls[m] += 1
        kind, prm = behavior(m); el = time.time() - T0[0]
        with ilock:
            inflight[m] += 1; max_inflight[m] = max(max_inflight[m], inflight[m])
        try: self._serve(m, kind, prm, el)
        finally:
            with ilock: inflight[m] -= 1
    def _serve(self, m, kind, prm, el):
        def send(code, status=None):
            body = json.dumps({"data": {"status": status}} if status else {}).encode()
            self.send_response(code); self.send_header("content-type", "application/json"); self.send_header("content-length", str(len(body))); self.end_headers()
            self.wfile.write(body)
        if kind == "timeout": time.sleep(1.0); return send(200, "CHECKING")
        if kind == "slow": time.sleep(prm); return send(200, "CHECKING")
        if kind == "timeout_once":
            if polls[m] == 1: time.sleep(prm); return send(200, "CHECKING")
            return send(200, "ISSUED")
        if kind == "always503": return send(503)
        if kind == "flaky": return send(503) if polls[m] <= prm else send(200, "ISSUED")
        if kind == "ended_get": return send(200, "ENDED")
        if kind == "never": return send(200, "CHECKING")
        if kind == "after": return send(200, "SOLD_OUT" if el >= prm else "PENDING")
        return send(200, "ISSUED")
    def log_message(self, *a): pass

def post_line(m, cat, sent, kind="admission", target=1, responded=None):
    d = {"kind": kind, "memberId": m, "target": target, "category": cat, "sentAtMs": sent}
    if responded is not None: d["respondedAtMs"] = responded
    return json.dumps(d)

class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H); cls.srv.handle_error = lambda *a: None; cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
    @classmethod
    def tearDownClass(cls): cls.srv.shutdown()
    def run_observer(self, extra_lines=(), budget=1500, grace=3500, base_lines=None, extra_args=()):
        polls.clear(); max_inflight.clear(); T0[0] = time.time(); sent = int(T0[0] * 1000)
        lines = base_lines(sent) if base_lines else [post_line(BASE + i, "success", sent) for i in range(9)] + [post_line(BASE + 9, "event_closed", sent)]
        lines = lines + list(extra_lines)
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); (t / "k6-failures.log").write_text("\n".join(lines))
            r = subprocess.run([sys.executable, "-B", str(HERE / "v1-observer.py"), "--post-log", str(t / "k6-failures.log"), "--targets", f"http://127.0.0.1:{self.port}",
                                "--event-id", "2", "--out-dir", str(t / "o"), "--budget-ms", str(budget), "--grace-ms", str(grace), "--interval-ms", "100",
                                "--timeout-ms", "300", "--max-rps", "500", "--concurrency", "16", "--assumed-latency-ms", "50", "--assumed-latency-source", "conservative-assumption", "--record-poll-windows", *extra_args], capture_output=True, text=True, timeout=60)
            self.assertEqual(r.returncode, 0, r.stderr)
            recs = {json.loads(l)["memberId"]: json.loads(l) for l in (t / "o/observer-records.jsonl").read_text().splitlines()}
            return json.loads((t / "o/observer-summary.json").read_text()), recs
    def test_all_states(self):
        s, r = self.run_observer()
        c = lambda i: r[BASE + i]["classification"]
        self.assertEqual(c(0), "terminal_within_budget"); self.assertTrue(r[BASE]["firstPollAlreadyTerminal"])
        self.assertEqual(c(1), "terminal_within_budget"); self.assertFalse(r[BASE + 1]["firstPollAlreadyTerminal"])
        self.assertGreaterEqual(r[BASE + 1]["firstPostToTerminalObservedMs"], 550)
        self.assertEqual(c(2), "terminal_late_after_budget"); self.assertGreater(r[BASE + 2]["firstPostToTerminalObservedMs"], 1500)
        self.assertEqual(c(3), "never_terminal_observed"); self.assertGreater(r[BASE + 3]["polls"], 5)
        self.assertEqual(c(4), "unreachable_all_polls_failed"); self.assertIn("http_503", r[BASE + 4]["errors"]); self.assertEqual(r[BASE + 4]["ok_polls"], 0)
        self.assertEqual(c(5), "unreachable_all_polls_failed"); self.assertIn("timeout", r[BASE + 5]["errors"])
        self.assertEqual(c(6), "terminal_within_budget"); self.assertEqual(r[BASE + 6]["errors"].get("http_503"), 2)   # 일시 오류 후 확정, 오류는 계수에 남는다
        self.assertEqual(c(7), "never_terminal_observed"); self.assertGreater(r[BASE + 7]["endedResponses"], 0)       # GET 의 ENDED 는 최종이 아니다
        self.assertEqual(c(9), "business_ended"); self.assertEqual(polls[BASE + 9], 0)                               # ENDED POST 회원은 폴링하지 않는다
        self.assertEqual(s["denominator_first_posts"], 10); self.assertEqual(sum(s["classification"].values()), 10)
        self.assertEqual(s["slo"]["success_terminal_within_budget"], 4)   # 0,1,6,8
        ns = s["slo"]["not_counted_as_success"]
        self.assertEqual(ns["never_terminal_observed"] + ns["unreachable_all_polls_failed"], 4)   # 3,7 / 4,5
        self.assertGreater(s["polls"]["total"], 20); self.assertLessEqual(s["polls"]["configured_max_rps"], 500)
    def test_duplicate_member_counted_once_in_denominator(self):
        sent = int(time.time() * 1000)
        s, r = self.run_observer(extra_lines=[post_line(BASE, "success", sent)])   # 같은 회원의 두 번째 admission 기록
        self.assertEqual(s["denominator_first_posts"], 10); self.assertEqual(s["input"]["members_with_multiple_admission_records"], 1)
    def test_unparseable_lines_reported_not_hidden(self):
        s, r = self.run_observer(extra_lines=['{"kind": "admission", broken'])
        self.assertEqual(s["input"]["post_log_parse_errors"], 1)
    def test_first_request_chosen_by_sentAt_not_file_order(self):
        # 로그는 응답 완료 순서: 늦게 끝난 앞선 timeout 기록이 뒤에, 후속 ENDED 가 앞에 있다
        def lines(sent):
            return [post_line(BASE + 10, "event_closed", sent + 800), post_line(BASE + 10, "client_timeout", sent)]
        s, r = self.run_observer(base_lines=lines, budget=1200, grace=800)
        rec = r[BASE + 10]
        self.assertEqual(rec["firstRequestResult"], "client_timeout")        # sentAtMs 가 이른 기록이 '최초'
        self.assertNotEqual(rec["classification"], "business_ended")   # 후속 ENDED 가 이전 uncertain 을 해결하지 않는다 -> 폴링됨
        self.assertGreater(rec["polls"], 0)
        self.assertEqual(s["input"]["members_where_file_order_first_differs_from_earliest_sentAtMs"], 1)
        self.assertEqual(s["input"]["members_with_conflicting_categories"], 1)
        self.assertEqual(s["denominator_first_posts"], 1)
    def test_only_ended_records_are_not_polled(self):
        def lines(sent): return [post_line(BASE + 10, "event_closed", sent), post_line(BASE + 10, "event_closed", sent + 5)]
        s, r = self.run_observer(base_lines=lines, budget=600, grace=0)
        self.assertEqual(r[BASE + 10]["classification"], "business_ended"); self.assertEqual(polls[BASE + 10], 0)
    def test_missing_sentAt_is_reported(self):
        def lines(sent): return [json.dumps({"kind": "admission", "memberId": BASE, "target": 1, "category": "success"})]
        s, r = self.run_observer(base_lines=lines, budget=600, grace=0)
        self.assertEqual(s["input"]["admission_records_missing_or_invalid_sentAtMs"], 1); self.assertEqual(r[BASE]["classification"], "slo_indeterminate")
    def test_single_inflight_per_member_with_slow_responses(self):
        # 응답(0.4s) > interval(0.1s): 회원당 동시 polling 이 생기면 안 되고, 발송 창이 겹치면 안 된다
        def lines(sent): return [post_line(BASE + 11, "success", sent)]
        s, r = self.run_observer(base_lines=lines, budget=2000, grace=0, extra_args=("--timeout-ms", "1500"))   # timeout > 응답시간: 클라이언트가 포기한 요청이 서버에 남는 경우와 구분
        self.assertEqual(max_inflight[BASE + 11], 1)
        w = r[BASE + 11]["pollWindows"]; self.assertGreaterEqual(len(w), 3)
        for (s0, e0), (s1, e1) in zip(w, w[1:]): self.assertGreaterEqual(s1, e0)    # 이전 완료 후에만 다음 발송
    def test_deadline_rechecked_right_before_send(self):
        # concurrency 1 + 낮은 max-rps 로 대기열을 만들어, 대기 후 deadline 이 지난 요청은 발송되지 않아야 한다
        def lines(sent): return [post_line(BASE + 20 + i, "success", sent) for i in range(8)]
        s, r = self.run_observer(base_lines=lines, budget=400, grace=0, extra_args=("--max-rps", "5", "--concurrency", "1"))
        sent_ok = True
        for m, rec in r.items():
            for s0, e0 in rec["pollWindows"]: sent_ok &= s0 <= rec["firstSentAtMs"] + 400 + 60   # 발송 시각이 deadline 을 넘지 않음(시계 허용 60ms)
        self.assertTrue(sent_ok)
        self.assertGreater(s["polls_skipped_past_deadline"], 0)
    def test_first_ended_but_duplicate_success_is_polled_and_fields_preserved(self):
        def lines(sent): return [post_line(BASE + 13, "event_closed", sent), post_line(BASE + 13, "success", sent + 50, kind="duplicate")]
        s, r = self.run_observer(base_lines=lines, budget=1500, grace=0)
        rec = r[BASE + 13]
        self.assertEqual(rec["firstRequestResult"], "event_closed")            # 최초 요청 결과(ENDED)는 보존
        self.assertEqual(rec["applicationResult"], "ISSUED")                   # 이후 application 결과는 별도 필드
        self.assertEqual(rec["classification"], "terminal_within_budget"); self.assertGreater(polls[BASE + 13], 0)
        self.assertEqual(s["denominator_first_posts"], 1)
        self.assertEqual(s["input"]["members_first_request_ended_but_other_attempt_not_ended(polled)"], 1)
    def test_all_ended_admission_and_duplicate_not_polled(self):
        def lines(sent): return [post_line(BASE + 13, "event_closed", sent), post_line(BASE + 13, "event_closed", sent + 5, kind="duplicate")]
        s, r = self.run_observer(base_lines=lines, budget=600, grace=0)
        self.assertEqual(r[BASE + 13]["classification"], "business_ended"); self.assertEqual(polls[BASE + 13], 0)
        self.assertEqual(s["slo"]["success_terminal_within_budget"], 0)     # ENDED 는 ISSUED/SOLD_OUT 성공이 아니다
    def test_partial_or_invalid_sentAt_is_indeterminate_but_in_denominator(self):
        def lines(sent):
            return [post_line(BASE + 13, "success", sent), json.dumps({"kind": "admission", "memberId": BASE + 13, "target": 1, "category": "client_timeout"}),   # 한 건 시각 누락
                    json.dumps({"kind": "admission", "memberId": BASE + 14, "target": 1, "category": "success", "sentAtMs": "yesterday"}),                   # 유효하지 않은 시각
                    post_line(BASE + 0, "success", sent)]
        s, r = self.run_observer(base_lines=lines, budget=1500, grace=0)
        self.assertEqual(r[BASE + 13]["classification"], "slo_indeterminate"); self.assertEqual(r[BASE + 14]["classification"], "slo_indeterminate")
        self.assertEqual(r[BASE + 0]["classification"], "terminal_within_budget")
        self.assertEqual(s["denominator_first_posts"], 3); self.assertEqual(s["slo"]["success_terminal_within_budget"], 1)   # indeterminate 는 성공 제외, 분모 유지
        self.assertEqual(s["slo"]["not_counted_as_success"]["slo_indeterminate"], 2)
        self.assertIsNotNone(r[BASE + 13]["terminalObservedAtMs"])           # 관찰 사실은 기록하되 SLO 판정에서만 제외
    def test_reconnects_normally_after_timeout(self):
        def lines(sent): return [post_line(BASE + 12, "success", sent)]
        s, r = self.run_observer(base_lines=lines, budget=3000, grace=0)
        rec = r[BASE + 12]
        self.assertEqual(rec["errors"], {"timeout": 1})                       # 가짜 connection_error/CannotSendRequest 없음
        self.assertEqual(rec["classification"], "terminal_within_budget")
        self.assertGreaterEqual(s["resources"]["connections_opened"], 2)
    def test_fixed_pool_reuses_and_closes_connections(self):
        def lines(sent): return [post_line(BASE + 30 + i, "success", sent) for i in range(30)]    # 30명 never(CHECKING 지속)
        s, r = self.run_observer(base_lines=lines, budget=1500, grace=0, extra_args=("--concurrency", "4"))
        res = s["resources"]
        self.assertGreater(s["polls"]["total"], 60)
        self.assertEqual(res["pool_threads"], 4); self.assertLessEqual(res["peak_active_threads_in_process"], 4 + 3)
        self.assertLessEqual(res["connections_opened"], 4 + 2); self.assertLessEqual(res["max_open_connections"], 4)   # 폴링 수가 아니라 pool 크기에 비례
        self.assertEqual(res["open_connections_at_exit"], 0); self.assertEqual(res["connections_opened"], res["connections_closed"])
    def test_observation_budget_insufficient_is_flagged_and_can_refuse(self):
        def lines(sent): return [post_line(BASE + 30 + i, "success", sent) for i in range(30)]
        s, r = self.run_observer(base_lines=lines, budget=1000, grace=0, extra_args=("--max-rps", "5"))     # 30명/5rps=6s > 1s
        self.assertTrue(s["observation_budget"]["observation_budget_insufficient"])
        # 이미 deadline 이 지난 로그(관찰 시작 지연)
        polls.clear(); old = int((time.time() - 600) * 1000)
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); (t / "l.log").write_text("\n".join(post_line(BASE + 30 + i, "success", old) for i in range(5)))
            rr = subprocess.run([sys.executable, "-B", str(HERE / "v1-observer.py"), "--post-log", str(t / "l.log"), "--targets", f"http://127.0.0.1:{self.port}", "--event-id", "2",
                                 "--out-dir", str(t / "o"), "--budget-ms", "1000", "--assumed-latency-ms", "50", "--assumed-latency-source", "conservative-assumption", "--require-budget"], capture_output=True, text=True, timeout=30)
            self.assertEqual(rr.returncode, 4); out = json.loads((t / "o/observer-summary.json").read_text())
            self.assertTrue(out["not_run"]); self.assertEqual(sum(polls.values()), 0)
    def test_assumed_latency_is_required(self):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); (t / "l.log").write_text(post_line(BASE, "success", int(time.time() * 1000)))
            rr = subprocess.run([sys.executable, "-B", str(HERE / "v1-observer.py"), "--post-log", str(t / "l.log"), "--targets", f"http://127.0.0.1:{self.port}",
                                 "--event-id", "2", "--out-dir", str(t / "o"), "--assumed-latency-source", "conservative-assumption"], capture_output=True, text=True, timeout=30)
            self.assertEqual(rr.returncode, 2); self.assertIn("assumed-latency-ms", rr.stderr)
    def test_post_run_verdict_overrides_optimistic_pre_run_estimate(self):
        def lines(sent): return [post_line(BASE + 60 + i, "success", sent) for i in range(30)]       # GET 이 0.3s 걸리는 30명
        s, r = self.run_observer(base_lines=lines, budget=1500, grace=0, extra_args=("--concurrency", "2", "--assumed-latency-ms", "1", "--timeout-ms", "1500"))
        self.assertFalse(s["observation_budget"]["observation_budget_insufficient"])                    # 낙관적 가정(1ms)으로는 '충분'
        post = s["observation_budget_post_run"]
        self.assertTrue(post["post_run_observation_budget_insufficient"]); self.assertTrue(post["overrides_pre_run_verdict"])
        self.assertEqual(post["authoritative_verdict"], "insufficient")
        self.assertLess(post["achieved_rps_average_over_poll_span"], 10)                                                       # 실제 달성 rps 로 재계산
    def test_adr001_metrics_reported_side_by_side(self):
        s, r = self.run_observer()
        m = s["adr001_metrics"]
        self.assertEqual(m["issuance_result_coverage"]["count"], 4)
        # 기본 합성 로그의 ENDED 회원은 respondedAtMs 가 없다 -> business_ended 는 있지만 시각 누락이라 수렴 분자에 합치지 않는다
        self.assertEqual(m["business_outcome_convergence"]["count"], 4)
        self.assertEqual(m["business_outcome_convergence"]["business_ended_untimed_not_counted"], 1)
        self.assertEqual(m["business_outcome_convergence"]["of"], 10)
    def run_cli(self, *extra, lines=None):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); (t / "l.log").write_text("\n".join(lines or [post_line(BASE, "success", int(time.time() * 1000))]))
            rr = subprocess.run([sys.executable, "-B", str(HERE / "v1-observer.py"), "--post-log", str(t / "l.log"), "--targets", f"http://127.0.0.1:{self.port}",
                                 "--event-id", "2", "--out-dir", str(t / "o"), "--budget-ms", "1000", *extra], capture_output=True, text=True, timeout=30)
            summ = (t / "o/observer-summary.json")
            return rr, (json.loads(summ.read_text()) if summ.exists() else None)
    def test_assumed_latency_must_be_positive_and_source_documented(self):
        rr, _ = self.run_cli("--assumed-latency-ms", "0", "--assumed-latency-source", "conservative-assumption"); self.assertEqual(rr.returncode, 2)
        rr, _ = self.run_cli("--assumed-latency-ms", "100", "--assumed-latency-source", "measured-get-smoke"); self.assertEqual(rr.returncode, 2)       # sample-n/조건 없음
        rr, _ = self.run_cli("--assumed-latency-ms", "100", "--assumed-latency-source", "post-p95"); self.assertEqual(rr.returncode, 2)                   # POST p95 출처 거부
    def test_assumed_latency_source_is_recorded_in_summary(self):
        rr, s = self.run_cli("--assumed-latency-ms", "250", "--assumed-latency-source", "measured-get-smoke", "--assumed-latency-sample-n", "200",
                             "--assumed-latency-condition", "idle apps, 50rps GET smoke")
        self.assertEqual(rr.returncode, 0, rr.stderr)
        al = s["observation_budget"]["assumed_latency"]
        self.assertEqual((al["ms"], al["source"], al["provisional"], al["sample_n"]), (250, "measured-get-smoke", False, 200)); self.assertIn("idle apps", al["condition"])
        rr, s = self.run_cli("--assumed-latency-ms", "3000", "--assumed-latency-source", "conservative-assumption")
        al = s["observation_budget"]["assumed_latency"]; self.assertTrue(al["provisional"]); self.assertEqual(al["ms"], 3000)
    def test_business_outcome_convergence_requires_valid_ended_times(self):
        now = int(time.time() * 1000)
        lines = [post_line(BASE + 70, "event_closed", now, responded=now + 100),                                                     # 시각 유효 + budget 내 -> 카운트
                 post_line(BASE + 71, "event_closed", now),                                                                           # respondedAtMs 누락 -> untimed
                 post_line(BASE + 72, "event_closed", now, responded=now + 5000),                                                      # budget 초과 -> late
                 post_line(BASE + 73, "event_closed", now, responded=now + 100), post_line(BASE + 73, "event_closed", now, kind="duplicate"),   # duplicate ENDED 시각 누락 -> untimed
                 post_line(BASE + 74, "client_timeout", now, responded=now + 100)]                                                    # 이전 uncertain -> 폴링 대상, ENDED 아님
        rr, s = self.run_cli("--assumed-latency-ms", "50", "--assumed-latency-source", "conservative-assumption", "--grace-ms", "0", "--timeout-ms", "300", lines=lines)
        self.assertEqual(rr.returncode, 0, rr.stderr)
        c = s["adr001_metrics"]["business_outcome_convergence"]
        self.assertEqual((c["business_ended_counted"], c["business_ended_untimed_not_counted"], c["business_ended_late_not_counted"]), (1, 2, 1))
        self.assertEqual(s["adr001_metrics"]["issuance_result_coverage"]["count"] + c["business_ended_counted"], c["count"])
        self.assertEqual(s["denominator_first_posts"], 5)
    def test_post_run_flag_false_when_everything_observed_early(self):
        def lines(sent): return [post_line(BASE + i, "success", sent) for i in (0, 8)]       # 즉시 terminal -> 평균 rps 는 낮아도 관찰 부족 아님
        s, r = self.run_observer(base_lines=lines, budget=3000, grace=0)
        self.assertFalse(s["observation_budget_post_run"]["post_run_observation_budget_insufficient"])
        self.assertFalse(s["observation_budget_post_run"]["differs_from_pre_run_verdict"]); self.assertEqual(s["slo"]["success_terminal_within_budget"], 2)
    def test_get_latency_by_status_and_observer_cost_reported(self):
        s, r = self.run_observer()
        g = s["get_latency_ms"]
        self.assertGreater(g["http_200"]["n"], 10); self.assertIsNotNone(g["http_200"]["p95"])
        self.assertIn("503", g["by_http_status"])                                       # 오류 상태 응답도 상태별 지연으로 분리
        self.assertEqual(g["timeouts_or_connection_errors_not_in_latency"], s["polls"]["errors"].get("timeout", 0) + s["polls"]["errors"].get("connection_error", 0))
        n_total = sum(v["n"] for v in g["by_http_status"].values())
        self.assertEqual(n_total + g["timeouts_or_connection_errors_not_in_latency"] + s["polls"]["errors"].get("bad_json", 0), s["polls"]["total"])   # 모든 폴링이 지연 또는 오류로 계상
        o = s["observer_process"]
        self.assertGreater(o["wall_s"], 0); self.assertGreaterEqual(o["cpu_s"], 0); self.assertGreater(o["max_rss_mib"], 1)
        self.assertLess(o["avg_cpu_fraction"], 1.5)
if __name__ == "__main__": unittest.main()
