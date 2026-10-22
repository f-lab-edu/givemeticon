# Stages

## S0 - read-then-decrement (frozen)

* Structure: 한 `@Transactional` 안에서 `selectRemaining` -> `decrementRemaining` -> `insertIssue`; 락·조건부 UPDATE·유니크 키 없음.
* Test A (consistency, 1000 threads, stock 100, 10 repetitions): 10/10 깨짐, 매 회 107건 발급 (초과 7건), `dup_members` 0.
  Evidence: `coupon-lab/evidence/s0/consistency/20261022-122045-49d4d5b/consistency.json`,
  `coupon-lab/evidence/s0/consistency/20261022-122106-f940c21/consistency.json`
* Diagnosis: "stale check" - 판단(remaining > 0)이 읽은 시점의 값에 근거하고, 그 값은 다른 트랜잭션이 차감하는 순간 이미 낡아 있다.
