# #166 자원 제한 baseline 환경 준비 기록 (비실행·비측정 문서)

이 문서는 측정 결과가 아니다. 부하 실행은 환경 메모리 제약으로 보류되었고, 아래는 준비물과 정적/대체 실행 검증 결과다.

## 준비물 (branch `verifier/issue-166-capped-env`, 운영 코드 무변경)
- `scripts/verifier-isolated/capped/run-capped-v1.sh`: V1(묶음 접수+묶음 발급). 앱2/MySQL/Redis2 컨테이너에 `--cpus/--memory/--memory-swap`, `--oom-score-adj=1000`, managed 라벨. 생성기는 host(soft: GOMAXPROCS/GOMEMLIMIT) 또는 container(강제 cap). host 생성기 결과는 **동일 총자원 비교 PASS로 분류하지 않는다**.
- `scripts/verifier-isolated/capped/run-capped-stock.sh`: stock(Redisson+MySQL) 동기/accept 경로. `mysql-loadtest,redis-lock-loadtest` 프로필에 JDBC/Redis/Kafka 고정값을 환경변수·명령행으로 격리 인스턴스에 덮어쓴다.
- `scripts/verifier-isolated/stock-arrival-record.js`, `analyze-stock.py`: 회원별 원본 기록·DB 대조(200 응답 ≠ DB 확정, 클라이언트 미확인 DB 발급 분리, 중복/초과/누락 무결성).
- `capped/memory-guard.sh`: 사전 거부 가드. 계획 자원(앱 2 + DB + Redis 2 + container 생성기) + 여유분(기본 768MiB) > Docker VM 가용 메모리면 exit 3, 컨테이너·네트워크 생성 0. 무관 컨테이너 보호를 보장하는 장치가 아니며(oom-score, 사후 중단 포함) 계획 예산 대비 사전 점검일 뿐이다.

## 정적/대체 실행 검증 (실제 컨테이너·부하 없음)
- `capped/test-memory-guard.sh` 10건: 관측값 1449MiB 거부, 경계값(2911/2912), 단위 파싱, container 생성기 합산, 여유분 override.
- `capped/test-capped-runners.sh` 21건(stub docker): 두 runner 모두 사전 거부 시 docker 호출 0, `up` 시 5개 컨테이너 전부 oom-score+memory-swap, 비밀번호 sentinel이 docker argv에 없음, 공유 인프라(givemeticon-*, 3306/6379/6380/9092) 미참조, 전용 네트워크, `down`은 라벨 일치 소유 컨테이너만 stop→rm(force 없음)·불일치는 미접촉, stock 프로필/redis/kafka/JDBC 덮어쓰기.
- `test-analyze-stock.py` 6건(합성 로그): 정상/초과발급/timeout-but-DB발급/parse오류·누락/중복 최초요청/200-but-DB없음.
- `VerifierStockConfigurationTest` 2건(Spring 설정 로딩, 인프라 접속 없음): 덮어쓰기가 유효 설정에서 이김 + 음성 대조(프로필 단독은 공유 6379/6380/9092/3306을 가리킴).
- 전체 `./gradlew test`: 250 tests, failures 0, errors 0, skipped 0 (이 branch의 HEAD+작업트리 기준; 아래 "귀속" 참조).

## 추가 보완 (Architect 지시 반영)
- **동일 workload 계약**: `stock-arrival-record.js`는 이제 V1과 같은 결정적 중복(sequence % round(1/DUPLICATE_RATE)==0, 같은 회원이 다른 앱으로 2번째 POST, 기본 10%)을 보내며 회원/시도(`attempt` 1|2)별 한 줄 JSON과 실제 전송창 내/밖(`inWindow`, scenario 시작~+duration) 플래그를 남긴다. `DUPLICATE_RATE=0`은 별도 보조(고유 요청만) 실험이다. k6는 `rate*duration+1`번째 반복이 t=duration 경계(+1ms 관측)에 실행되어 창 밖으로 분류될 수 있다(소규모 기능 확인에서 관측; 부하 아님).
- `compare-workload.py` + 테스트 5건: V1/stock 원본 로그의 최초 요청 수·offset·앱 분배·중복 offset/대상 앱이 같은지 대조. 이미 보존된 실제 V1 로그(rep1)와 stock 스크립트 소규모 출력(41 iterations)의 공통 구간이 일치함을 별도로 확인.
- **락 지표**(`analyze-lock-metrics.py` + 테스트 5건): `coupon.redis_lock.acquire`(outcome)/`hold` 를 앱별 before/after .prom 에서 count/sum/bucket delta 로 수집, 누락·reset 구분(0 대체 금지), 합산은 reset 없음+bucket 집합 동일일 때만 bucket 단위로(앱별 p95 평균 금지, 분위는 le 상한 구간), `hold`는 REQUIRES_NEW 트랜잭션 경계 전체(DB 획득~commit 포함)이므로 순수 Redis 시간이 아님, tryLock 비-interrupt 예외는 timer 밖이므로 http 요청 수와 앱 로그로 대조(`untimed_estimate`, 0 가정 금지). 기본 wait 5s/lease 3s, 동일 지표를 async worker·recovery worker 사이트도 쓰므로 `lock-annotation-sites.txt`와 worker 모드(off)를 manifest에 기록.
- **down/remove 분리**: `down`은 소유 라벨 전수 검증 후 정상 stop만(삭제·네트워크 제거 없음), `remove`는 소유·정지 확인 후 force 없이 삭제. `up`은 기존 이름/네트워크가 있으면 거부, 부분 실패 시 이번에 만든 컨테이너만 라벨 재검증 후 stop하고 원래 exit 보존(/bin/bash 3.2에서 빈 배열 안전).
- **Linux(hosted runner) 차이 대응**: 가드는 Docker Desktop이 아니면 호스트 /proc/meminfo MemAvailable 을 사용(native-linux-host-meminfo), 포트 점검은 lsof→ss→netstat 폴백, manifest 는 sysctl/vm_stat 대신 nproc·/proc/meminfo·cgroup 값 사용. 가드 범위 고지: Docker VM(또는 native 호스트) 메모리만 보며 호스트 전체 메모리, host k6 soft 제한, observer 예산은 합산에 없다 → 통과가 전체 안전 보장이 아님.

## 보존한 증거
- `RESEARCH/verifier-runs/capsmoke-1007-1534/` (EVIDENCE.md, SHA256SUMS): MySQL 768m 한도에서 OOMKilled(exit 137) 원본 inspect/log. 측정에 사용하지 않음, 원인 미확정.

## 한계/미확정
- Docker VM 가용 메모리 ≈1.4GB(관측) 대비 최소 계획 ≈2.9GB(2.1GB+여유 768MiB) -> 현재 환경에서는 가드가 실행을 거부한다. 실행 재개 조건: 런타임 메모리 여유가 계획 예산을 충족한다는 새 증거.
- 무관 컨테이너 재시작과 이전 uncapped 실행의 인과는 **미확정**.
- 50k/100k 생성 가능 여부는 이 환경에서 미검증이다(영구 불가 주장 아님).
