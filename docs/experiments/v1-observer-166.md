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
