---
title: "Verifier 독립 대조 - 보정 run 38096702816 (SHA b1e08f7)"
tags: [verifier, calibration, issue-177, artifact-review]
status: active
created: 2026-10-11
---

# Verifier 독립 대조 — hosted 보정 run 38096702816

**범위: 보정 capability smoke (stock·V1 각 100rps × 2초, 새 fixture, 예열 없음, 단일 실행). baseline·SLO PASS·구조 간 비교·10k 수용 증거가 아니다.**
원본 artifact는 수정하지 않았다. 아래 값은 원본 `.prom`/`.json`/`k6` 로그에서 재계산한 값이다(패키징된 분석기 출력만 신뢰하지 않음).

## 0. 원본 위치와 식별
- run: https://github.com/f-lab-edu/givemeticon/actions/runs/38096702816 (push, 승인 branch, head `b1e08f7d0093c9431f735f8b21639c19e9186229`, 5분 36초, success)
- 로컬 사본: `RESEARCH/ISSUE_177_CALIBRATION_RUN_38096702816_verifier/issue177-b-calibration-b1e08f7d0093c9431f735f8b21639c19e9186229-1/` (`stock-smoke/run`, `v1-smoke/run`, `SHA256SUMS`)
- 준비 PR #180 head는 `3989547` 그대로, 실제 실행은 복구 commit `b1e08f7`.

## 1. 무결성·소유·정리
| 항목 | 결과 |
|---|---|
| SHA256SUMS | 141/141 OK |
| checked-sha | = run head `b1e08f7` |
| step 결과 | 전부 success |
| 수집 | `collection-status.json` = COLLECTED_REDACTED (exit 0); 48-hex credential 모양 0건, `[REDACTED]` 마커 1건 |
| 정리 | down/remove exit 0/0, OOM 0, restart 0 |

## 2. cap·scope (처음으로 실제 Linux 증거)
- 서비스 컨테이너(stock·V1 각각): app 0.55 CPU/768 MiB, MySQL 1.0/1024 MiB, Redis 0.05/64 MiB — `docker inspect` + 컨테이너 내부 `cpu.max`/`memory.max`/`swap.max` 일치 (`pass=true`).
- 생성기 0.75 CPU/3072 MiB: live cgroup 표본 11(stock)/4(V1)개로 확인.
- observer systemd scope: `cpu.max 25000 100000`, `memory.max 536870912`, swap 0 — 초기·GET 전·GET 후·최종 4회 모두 pass.

## 3. 부하 생성 (두 경로 동일)
- 첫 요청 201 = 창 안 200 (`rate × duration`) + 경계 1 (t = duration). 중복 21 = 결정적 규칙 `seq % round(1/0.10) == 0` 의 기대값과 정확히 일치(누락/예상외 0). drop 0.
- 최대 VU 184(stock)/84(V1) ≤ 201 (유한 창 상한 N = 201). 로그 파싱 오류 0.
- 생성기 throttling 0 (71/23 periods), k6 메모리 피크 70/80.7 MiB — **표본 기반 하한**. VU·메모리 확장 근거로 일반화하지 않는다.

## 4. stock (Redisson + MySQL)
- 클라이언트: 200 응답 90, http_error 111 (모두 서버 429). 응답시간 p50 5.03 s (성공만 p50 3.04 s, p95 5.09 s).
- DB 쿠폰 98, 중복·초과 0. "200인데 DB 없음" 0. **DB에는 발급됐으나 클라이언트가 200을 보지 못한 요청 8건**(별도 분류).
- 락 지표 (원본 after scrape raw, 공식: 평균 = Δsum/Δcount, 합산은 count/sum 가중. **before에 시리즈가 없어 baseline 0을 가정한 값 = §7의 조건부 수동 추론이며 직접 관측이 아니다**):

| app | outcome | Δcount | 평균 대기 |
|---|---|---|---|
| app1 | acquired | 49 | 3,627 ms |
| app1 | rejected | 52 | 5,001 ms |
| app2 | acquired | 62 | 2,462 ms |
| app2 | rejected | 59 | 5,014 ms |

  hold(REQUIRES_NEW 트랜잭션 전체 포함): app1 49건 평균 37.9 ms, app2 62건 평균 50.3 ms.
  전체 시도 222건 중 rejected 111건 (50.0%); rejected 평균 대기 ≈ wait 한도 5 s.
- 정합: 서버 429 111 = 클라이언트 http_error 111.
- 자원: 앱 throttling periods 47%/43%, MySQL 0%, Redis ≈ 8%.
- **원인 UNKNOWN**: 예열 없는 cold JVM, 락 직렬화(≈ 1/hold), 앱 CPU cap 효과를 이 데이터로 분리할 수 없다. (Reviewer 가설: rate 변화 실험으로 판별, 후속 계약에서 다룸. 같은 cap에서 rate만 바꾸면 throttling도 같이 변하므로 단독 확정 불가.)

## 5. V1 (묶음 접수)
- 201건 전원 ISSUED (미확정 0, 중복·초과 0, ENDED 없음: 재고 1000 > 201).
- 접수 응답 p50 793 ms / p95 1.11 s / p99 1.20 s.
- 최초 POST → `status UPDATE ts`: p50 959 / p95 1,345 / max 1,545 ms. **`finalized_at`은 트랜잭션 내부 UPDATE 시각이며 commit/클라이언트 확인 시각이 아니다.**
- Hikari 최대 active 3, pending 0, timeout 0, acquire 평균 1.2 ms; scrape 실패 0, 간격 1.0 s.
- 서버측 묶음 대기 평균 ≈ 280/288 ms, tx 평균 ≈ 310/288 ms (app1/app2; before 시리즈 부재 → §7의 조건부 수동 추론 값, 직접 관측 아님).
- 앱 throttling 22~24%, MySQL 0%.
- 두 경로가 같은 계약(201/21)을 받았다는 사실 외에, 이 결과로 V1/RabbitMQ 우위 또는 개선율을 주장하지 않는다.

## 6. GET 보정 (SLO `NOT_APPLICABLE(calibration)`)
- idle: n = 40, p50 6 ms / p95 11 ms / p99·max 53 ms, 오류 0. observer 프로세스: wall 1.97 s, CPU 0.038 s, RSS 20.8 MiB. 공유 scope CPU delta 105 ms, 2/21 periods throttled (151 ms).
- POST와 실제로 겹친 GET: n = 1 (170 ms), scope 5/5 periods throttled (566 ms) → **부하 중 분포 아님, under-load 입력 미확보**.
- idle p95 11 ms는 `measured-get-smoke`의 provisional 입력으로만 사용 가능(유휴 조건). 부하 중 지연을 보장하지 않으며 SLO 증거가 아니다.

## 7. 분석기 결함과 정정 (fc2f27b 이후 개정: 이 절은 현재 SHA 기준)
**결함**: Micrometer는 meter를 첫 사용 시 등록한다. `*-before.prom`에 시리즈가 없으면 기존 분석기가 MISSING으로 처리했고, 락 reconciliation은 거짓 "untimed 101/121"을 보고했다. 해당 원출력 필드는 신뢰하지 않는다.

**정정 방식**: 다른 시리즈가 있다는 사실만으로는 0으로 보지 않는다. 두 종류의 근거가 **모두** 필요하고, 하나라도 없으면 MISSING이다.

*A. 본문에서 확인 가능한 범위 (structural)*
- `anchors_present`: process_start_time_seconds / process_uptime_seconds / jvm_info 가 before·after에 있음, 해석 불가 줄 0
- `same_process`: process_start_time 값·라벨 동일, uptime after ≥ before (재시작 없음 — 확인 가능한 범위)
- `no_counter_reset`: count/sum/total/bucket 시리즈 감소 없음 (LongTaskTimer `*_active_*` 제외)
- `exposure_unchanged`: before의 count/sum/total/bucket 시리즈가 after에서 사라지지 않음 (필터/라벨 변경 없음 — 확인 가능한 범위)

*B. 수집 시점에 기록된 외부 provenance (`<app>-scrape-provenance.json`, 스키마 `scrape-provenance/v1`)*
- `collection_success`: before/after exit_code 0, HTTP 200
- `provenance_present`: `schema == "scrape-provenance/v1"` (다른 schema는 거부)
- `target_verified`: run 계약의 `expected_target`(sidecar의 requested_target을 복사해 정답으로 삼지 않음)과 두 scrape의 `requested_target`·`effective_target`이 모두 일치 (redirect·잘못된 앱 포트 거부)
- `body_integrity`: 기록된 body_bytes/body_sha256 == 실제 파일. **수집 후 무결성 근거일 뿐, 서버가 모든 시리즈를 노출했다는 증명은 아니다.**
- `exposure_policy`: 기록의 존재·형식만 검사하며 주장의 참을 증명하지 않는다. 필수 필드: `custom_app_meter_filter_or_registry_remove_used=false`(**앱 코드의 custom filter/remove만** 뜻함), evidence 문자열, Spring Boot/Micrometer 버전, `registration_paths`, **`framework_meter_filters`(비어 있지 않은 목록)**, **`effective_metrics_config`(`max_uri_tags`>0 정수, `enable` 오버라이드 dict, `source_sha256`)**. 추가로 **추론 대상 family 자신의 `registration_paths[<family>]`가 비어 있지 않아야** 한다(다른 family의 경로로 대체 불가).
  - 배경: Spring Boot 3.1.3 actuator auto-config는 런타임에 `OnlyOnceLoggingDenyMeterFilter`(max-uri-tags 초과 uri 태그의 `http.server.requests` deny)와 `PropertiesMeterFilter`를 등록한다(Reviewer가 로컬 gradle 캐시 jar를 javap로 확인). 따라서 "필터 없음" boolean은 사실이 아니며 이전 키 `meter_filter_or_registry_remove_used`는 더 이상 인정하지 않는다.
  - **HTTP family(`http_server_requests`) 추가 조건**: provenance의 `http_uri_contract.allowed_uris`가 비어 있지 않고, after scrape에 관측된 모든 uri 태그가 그 안에 있으며, distinct uri 수가 유효 `max_uri_tags` 미만일 것. 이는 **필요조건일 뿐이고 과거 deny 부재를 확정하지 않는다**(제거·필터 변경·미노출 시리즈가 있으면 이 수가 전체 등록 시도를 대표하지 못함). 계약/유효 필터 근거가 부족하면 HTTP absent-before는 MISSING이다. framework 전체 검증기는 만들지 않았다.

*C. family별 등록 경로 (`LAZY_FAMILIES`)*: 모든 meter가 첫 기록 시 등록된다고 일반화하지 않고, 아래 family만 허용한다. Spring Boot 3.1.3(build.gradle) → Micrometer 1.11.x.
`coupon_redis_lock_acquire/hold`(DistributedLockAop:54/67/73, `Timer.builder().register` 호출 시), `coupon_admission_transaction/commit/post_lock_work`(CouponAdmissionTransactionTimers), `coupon_admission_batch_queue_wait`(CouponBatchAdmissionAcceptor:175), `http_server_requests`(Spring Boot 3.1 server observation이 첫 요청 완료 시 태그 조합별 Timer 등록 — **라이브러리 동작 서술이며 이 환경에서 jar 소스로 재검증하지 않았다**). 저장소 `src/main`에는 MeterFilter/registry.remove가 없다(git grep). `application.yml`에는 percentiles-histogram/slo 설정이 있다.

*측정 전 활동(prior activity)은 추론 조건이 아니다*: 같은 프로세스의 완전한 scrape에 특정 태그 시리즈가 없으면 프로세스 시작 후 한 번도 기록되지 않은 것이므로 예열 후에도 새 태그(예: `outcome=rejected`)의 baseline 0 추론이 성립할 수 있다. 활동량은 `diagnostics.non_actuator_http_count_before` 진단 필드로만 남긴다(예열 후 시리즈 존재 + 새 rejected 태그 추론이 양성 테스트).

*결과 상태*: `OK`(직접) / `OK_INFERRED_ZERO_BASELINE`(A+B+C 모두) / `CONDITIONAL_MANUAL_INFERENCE`(A만, `--allow-manual-inference` 지정 시. OK가 아니며 과거 수집 사실로 귀속하지 않는다) / `MISSING` / `RESET`.

*reconciliation(http − timer)*: 락 timer는 worker/recovery 등 모든 `@DistributedLock` 사이트를 센다(예: CreateCouponFacade:147 waitTime=0 사이트). loadtest URI만 세는 http와 대조하려면 provenance `lock_call_sites`(worker_disabled, recovery_disabled, evidence)가 필요하고, 없으면 `UNKNOWN`이다. 숫자가 같아도 결론으로 쓰지 않으며 `gap_numeric_diagnostic_only`로만 남긴다. HTTP before 부재도 같은 family 검사를 거친다.

**과거 artifact 재분석(원본 무수정, `reanalysis-inferred-baseline/`)**
- **설정 한계(소스/프로필 기준 bean 조건 판단이며 호출 여부·성능 영향의 증거가 아님)**: stock 앱은 `COUPON_ISSUE_WORKER_MODE=off`만 받았고 `CouponIssueRequestRecoveryScheduler`는 `coupon.issue-worker.enabled`(`matchIfMissing=true`)로 켜지므로 recovery 락 사이트는 설정상 활성이다. V1은 `coupon-admission` 프로필에서 `issue-worker.enabled=false`다. 즉 두 경로의 recovery 설정은 비대칭이다. mode=off는 recovery 비활성 증거로 쓸 수 없고, 로그에 호출 메시지가 없다는 사실만으로 비활성을 증명하지도 않는다. recovery가 실제 요청을 재처리했거나 측정 지연을 유발했다는 증거는 없으며, 호출 유무와 성능 영향은 미확정이다. 앞선 "동일 계약" 표현은 workload/cap 범위로 한정한다.
- run 38096702816 artifact에는 수집 exit/HTTP status/target/본문 해시/노출 정책/락 사이트 비활성에 대한 **수집 시점 provenance 파일이 없다.** 사후에 만들어 과거 사실로 귀속하지 않았다.
- `default-no-provenance/`: 락·V1 서버측 지표 전부 `MISSING`, reconciliation `UNKNOWN`.
- `conditional-manual/` (`--allow-manual-inference`): A(본문 검사) 통과 시 `CONDITIONAL_MANUAL_INFERENCE`. 값은 raw 재계산과 같지만 조건부 수동 추론이며 직접 관측이 아니다: stock acquired 49/62, rejected 52/59, 평균 3,627/2,462 ms · 5,001/5,014 ms, hold 37.9/50.3 ms; V1 묶음 대기 평균 280.3/287.7 ms. reconciliation은 락 사이트 근거가 없어 `UNKNOWN`(진단 갭 0/0).
- 따라서 **"timer 밖 예외 0"은 이 artifact에서 확정되지 않는다.** 이전 보고의 "untimed 0/0"은 추론 의존이었고, 현재 기준으로는 `UNKNOWN`이다.

## 8. 한계
100rps·2 s·n≈200, 예열 없음, 단일 실행(분산 불명), idle GET n = 40, 부하 중 GET 사실상 없음, 서비스 간 시계·commit 가시 시각 미검증, 10k 이상 admission 증거 아님, k6 메모리/VU 스케일링 추정 불가. 필수 10 s × 10k/50k/100k 단계는 미완료.

## 9. 재현
```bash
cd <repo>/scripts/verifier-isolated
python3 analyze-lock-metrics.py <artifact>/stock-smoke/run <out>/stock-lock [--allow-manual-inference]
PYTHONPATH=. python3 <out>/reanalyze-v1-server-metrics.py <artifact>/v1-smoke/run [--allow-manual-inference] > <out>/v1-server/server-metrics.json
```
