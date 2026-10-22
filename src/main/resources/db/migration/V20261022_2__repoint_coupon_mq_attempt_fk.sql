-- ADR-006: coupon_mq_attempt.event_id의 FK 부모를 coupon_event에서 접수 카운터로 옮긴다.
-- MQ 접수도 카운터 행을 먼저 FOR UPDATE로 잡으므로 attempt INSERT의 FK 검사가 발급의 coupon_event 잠금과 경합하지 않는다.
-- (event_id, mapped_member_id) -> coupon_application 복합 FK(fk_coupon_mq_attempt_application)와
-- coupon_mq_control의 FK(fk_coupon_mq_control_event)는 바꾸지 않는다.
-- V20261022에서 모든 행사에 카운터 행을 만들었고 기존 FK가 attempt.event_id를 coupon_event 행으로 제한해 왔다.
SET foreign_key_checks = 0;
ALTER TABLE coupon_mq_attempt
    DROP FOREIGN KEY fk_coupon_mq_attempt_event,
    ADD CONSTRAINT fk_coupon_mq_attempt_counter FOREIGN KEY (event_id) REFERENCES coupon_event_admission_counter(event_id),
    ALGORITHM=INPLACE, LOCK=NONE;
SET foreign_key_checks = 1;
