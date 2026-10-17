# RabbitMQ 결정·검증 근거

작업 상태와 이력은 [Issue](https://github.com/f-lab-edu/givemeticon/issues)·[PR](https://github.com/f-lab-edu/givemeticon/pulls)을 기준으로 한다. 이 색인은 결정·검증 자료만 연결한다.

- [ADR-001](../adr/ADR-001-baseline.md): V1/stock 구분, 분모·자원·관측·비교 조건.
- [ADR-002](../adr/ADR-002-distributed-lock.md): 대체 경로 검증과 caller 감사 전 기존 락 보존.
- [ADR-003](../adr/ADR-003-rabbitmq.md): 과거 검토·대안. 순서·복구 계약의 대체 범위는 ADR-004를 따른다.
- [ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md): quorum/SAC·DB commit 순번·confirm/ACK·실패·보존 계약.
- [상세 wire·readiness 계약](../tasks/issue-167-contract-review.md), [기준 소스 해시](../tasks/issue-167-contract-sources.json).
- [전달·최종 상태의 분모](RABBITMQ_DELIVERY_ACCOUNTING.md).
- [실제 baseline 진단](../experiments/baseline-10k-diagnostic.md), [측정의 한계](../decisions/baseline-and-evidence.md), [보존 ZIP 무결성](../decisions/artifact-preservation-20261017.md).

측정값을 다른 SHA·환경의 검증으로 옮기지 않는다. fast ENDED·success-only latency·DB UPDATE 시각은 각각 내구 접수·전체 분모·commit/client 완료의 증거가 아니다. 결측 CPU/pool 표본과 자원 상한 미강제 진단으로 병목을 단정하지 않는다. confirmed/unknown·queue/DB PENDING 양쪽과 최초 신청 전체 분모를 관측한다. 동일 예산의 RabbitMQ 전후 비교·실제 장애 행렬 실행은 이 설계 문서만으로 입증되지 않는다.
