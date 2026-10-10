# V1 회원별 최종 HTTP 관찰기 (#166, 비실행 준비 기록)

`scripts/verifier-isolated/v1-observer.py` — POST 생성기와 분리된 제한 동시성 observer. 앱/DB/컨테이너 부하 없이 합성 HTTP 서버로만 검증했다(측정 결과 아님).

- 입력: POST 원본 로그(`k6-failures.log`, `POLL_BUDGET_MS=0` 실행)의 모든 최초 POST 회원 = 분모. 중복 회원 기록은 1회로 집계하고 개수를 보고한다.
- 대상: `GET /test-support/coupon-events/{eventId}/applications/me` + `X-Coupon-Admission-Test-Member` (b941a04에 존재). stock 경로에는 GET이 없으므로 같은 조회 지원으로 꾸미지 않는다(stock timeout은 DB 결과만 있으면 client-unconfirmed).
- 최종 판정: HTTP 200 + `data.status ∈ {ISSUED, SOLD_OUT}`. CHECKING/PENDING은 비최종, GET의 ENDED도 최종 아님(`get_ended_responses`로 집계).
- POST가 ENDED(event_closed)였던 회원은 폴링하지 않고 `business_ended`로 별도 분류 — 그 미접수 요청의 업무 최종값이며 다른(이전) uncertain 시도를 해결하지 않는다.
- 분류: terminal_within_budget / terminal_late_after_budget / never_terminal_observed / unreachable_all_polls_failed / never_polled / business_ended / no_sent_time_in_log. 503·timeout·연결 오류는 계수하고 0으로 대체하지 않는다.
- observer 예산: `--max-rps`(token bucket)와 `--concurrency` 상한으로 POST 생성기를 방해하지 않게 하고, 달성 rps/회원별 최대 관측 간격을 그대로 보고한다. 터미널 관찰 시각은 관측 간격 해상도의 상한값이다. hosted manifest에 observer CPU/메모리를 별도 항목으로 잡아야 한다.
- **최초 요청 선택**: 회원의 '최초' 기록은 `sentAtMs`가 가장 이른 기록이다(로그 파일 순서는 응답 완료 순서일 수 있어 신뢰하지 않음). 파일 순서 첫 기록과 다른 회원 수, `sentAtMs` 누락 기록 수, 범주가 충돌하는 회원 수를 `input`에 보고한다. 회원의 모든 기록이 `event_closed`일 때만 폴링에서 제외한다 — 이전(또는 다른) timeout/CHECKING 기록이 하나라도 있으면 후속 ENDED가 이를 해결하지 않으므로 폴링한다.
- **회원당 in-flight 1개**: poll 완료 후에 재예약하므로 응답이 interval보다 느려도 같은 회원의 동시 polling이 없다. semaphore/rate 대기 뒤 실제 발송 직전에 deadline을 다시 확인하고 지난 요청은 보내지 않는다(`polls_skipped_past_deadline`). 클라이언트 timeout으로 포기한 요청은 서버에서 계속 처리될 수 있으므로 서버 쪽 중첩은 별개이며 `--record-poll-windows`로 발송/완료 시각을 남겨 확인한다.
- 시각 전제: POST 로그 `sentAtMs`와 같은 호스트 시계. 다르면 결과를 신뢰하지 않는다.
- 검증(합성): `test-v1-observer.py` — immediate/after(within)/late/never/503 always/timeout/flaky(503 후 확정)/GET ENDED/ENDED POST 미폴링/중복 회원/파싱 오류/역순 로그(이전 timeout→후속 ENDED)/ENDED만 있는 회원/sentAtMs 누락/느린 응답 시 회원당 단일 in-flight와 발송창 비중첩/deadline 직전 재확인.
- 미검증: 실제 앱 대상 동작, 대규모(10k+) 폴링 시 달성 rps와 서버 영향, hosted Linux 동작.

## 수정 2 (Reviewer MAJOR 1~3 + Architect 결정, 원본 12c009a 보존)
- **폴링 필요성**: 회원의 admission **과 duplicate** 기록 전부가 event_closed 일 때만 면제(`business_ended`). 최초 ENDED + 다른 시도 비ENDED 회원은 폴링하며, 최초 요청 결과(`firstRequestResult`)와 이후 application 결과(`applicationResult`)를 별도 필드로 보존한다. 그런 회원 수는 `members_first_request_ended_but_other_attempt_not_ended(polled)`로 보고한다. 분모는 최초 admission 회원 그대로.
- **`slo_indeterminate`**: sentAtMs 가 없거나 유효하지 않은(문자열/비현실적 값) admission 기록이 하나라도 있는 회원. 전체 분모에 남기고 within-budget 성공에서 제외하며(관찰 사실은 기록), p50/p95 계산에서도 제외한다.
- **연결/스레드**: 고정 worker pool(`--concurrency`)이 스레드별 연결을 재사용하고, timeout/오류/종료 시 close 한다(`CannotSendRequest` 방지). 요약에 `pool_threads`, `peak_active_threads_in_process`, `connections_opened/closed`, `max_open_connections`, `open_connections_at_exit` 보고. 회원당 client in-flight 1·발송 직전 deadline 재확인은 유지.
- **관찰 예산 점검(실행 전)**: 폴링 대상 수 / min(max-rps, concurrency/assumed-latency)로 1회전 시간을 구해 가장 이른 deadline 까지 남은 시간과 비교하고 `observation_budget_insufficient`를 명시한다(관찰 시작 지연 포함). `--require-budget`이면 부족 시 exit 4로 폴링 없이 종료. **가능 판정은 성공 보장이 아니다.** 성공 = 실제 terminal 을 budget 내에 본 회원뿐이며 never_polled/late/indeterminate/누락은 분모에서 빼지 않는다.
- 50k/100k 전수 예산이 맞지 않으면 관측 180초 SLO는 **미검증**으로 남긴다. 표본 관찰이 전수 180초 PASS를 대체하지 않으며 DB 대조는 별도로 유지하되 client 관찰을 대체하지 않는다.
- 합성 서버 검증(14건): 위 기존 항목 + 첫 ENDED/duplicate success, 모두 ENDED, 일부/유효하지 않은 sentAt, timeout 뒤 정상 재연결, 고정 pool의 연결/스레드 상한, 예산 부족 표식과 `--require-budget` 거부(폴링 0).

## 수정 3 (Reviewer MAJOR/MINOR + Architect 지시, 이전 SHA 04e5f02 보존)
- **지연 가정 입력**: `--assumed-latency-ms`는 양수 필수이고 `--assumed-latency-source`(필수)로 출처를 기록한다. `measured-get-smoke`는 실제 GET smoke의 p95이며 `--assumed-latency-sample-n`(>0)과 `--assumed-latency-condition`(smoke 조건)이 필요하다. `conservative-assumption`(예: GET timeout 값)은 `provisional=true`로 표시된다. POST p95를 대입하는 출처는 허용하지 않는다(argparse가 거부). 사전 점검은 feasibility estimate일 뿐이며 smoke의 GET 지연이 부하 중 지연을 보장하지 않는다. 입력은 summary `observation_budget.assumed_latency`에 남는다.
- **사후 판정(직접 증거)**: `observation_budget_post_run`은 한 번도 못 폴링한 회원 수, deadline 때문에 건너뛴 폴링 수, 끝내 미확정이면서 관측 간격이 budget을 넘은 회원 수로 `post_run_observation_budget_insufficient`를 계산하고 사전 판정과의 차이(`differs_from_pre_run_verdict`/`overrides_pre_run_verdict`)를 기록한다. 평균 rps는 참고값이며(대상이 빨리 수렴해 작업량이 줄어든 구간 포함) 이미 budget 내에 관찰된 terminal을 무효화하지 않는다.
- **두 지표 분리**: `issuance_result_coverage`(ISSUED/SOLD_OUT을 budget 내 관찰)와 `business_outcome_convergence`(위 분자 + business_ended). business_ended는 해당 회원의 **모든** 시도(admission+duplicate)의 ENDED 응답 시각(`respondedAtMs`)이 유효하고 firstSentAtMs 기준 budget 내일 때만 분자에 들어가며, 시각 누락(`business_ended_untimed_not_counted`)·초과(`..._late_not_counted`)·이전 uncertain·slo_indeterminate는 성공에 합치지 않는다. 최초 ENDED(`firstRequestResult`)와 이후 application 결과(`applicationResult`)는 한 회원 레코드의 별도 필드다.
- 합성 검증 21건: 위 항목 + 지연 입력 검증(0/누락 sample-n/POST 출처 거부), 출처 summary 기록, ENDED 시각 유효/누락/초과/duplicate 누락, 빨리 수렴한 경우 사후 판정 false, 낙관적 가정에서 사후 판정이 사전 판정을 뒤집는 경우.
