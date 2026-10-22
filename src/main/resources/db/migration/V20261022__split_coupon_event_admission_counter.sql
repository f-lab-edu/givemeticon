-- ADR-006: 접수 순번을 coupon_event에서 분리한 행사별 카운터 행으로 옮긴다.
-- 이전 바이너리(coupon_event.next_acceptance_sequence를 쓰는 버전)를 모두 멈춘 뒤에만 적용한다.
-- 두 쪽이 서로 다른 행으로 순번을 할당하면 같은 순번이 만들어질 수 있다.
CREATE TABLE coupon_event_admission_counter (
    event_id                 BIGINT      NOT NULL PRIMARY KEY,
    next_acceptance_sequence BIGINT      NOT NULL DEFAULT 0,
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_coupon_event_admission_counter_event FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    CONSTRAINT chk_coupon_event_admission_counter_seq CHECK (next_acceptance_sequence >= 0)
) ENGINE=InnoDB;

-- 기존 행사 전부의 카운터 행을 현재 순번으로 만든다. 이후 새 행사의 카운터 행은 행사를 만드는 쪽이 함께 만든다.
INSERT INTO coupon_event_admission_counter (event_id, next_acceptance_sequence, updated_at)
SELECT id, next_acceptance_sequence, UTC_TIMESTAMP(6) FROM coupon_event;

-- coupon_application.event_id의 FK 부모를 coupon_event에서 카운터로 옮긴다. 접수의 INSERT가 FK 검사로 요구하는
-- 공유 잠금이 접수가 이미 배타 잠금으로 쥔 카운터 행에 걸려 발급의 coupon_event 잠금과 경합하지 않는다.
-- foreign_key_checks를 끄면 FK 추가가 INPLACE(메타데이터만)로 가능하다. 이 경우 기존 행의 검증이 생략되지만,
-- 모든 coupon_event 행에 카운터 행이 위에서 만들어졌고 기존 FK가 application.event_id를 coupon_event 행으로
-- 제한해 왔으므로 고아 행은 생기지 않는다. 같은 이름으로 DROP과 ADD를 한 ALTER에 둘 수 없어 새 이름을 쓴다.
-- FK에 쓰이던 인덱스는 UNIQUE (event_id, member_id) 등 명시 인덱스라 FK를 지워도 남는다.
SET foreign_key_checks = 0;
ALTER TABLE coupon_application
    DROP FOREIGN KEY fk_coupon_application_event,
    ADD CONSTRAINT fk_coupon_application_counter FOREIGN KEY (event_id) REFERENCES coupon_event_admission_counter(event_id),
    ALGORITHM=INPLACE, LOCK=NONE;
SET foreign_key_checks = 1;

-- coupon_event.next_acceptance_sequence는 사용 중단(deprecated)이다. 값은 더 갱신되지 않고 코드도 읽지 않는다.
-- 컬럼 제거는 후속 변경이다. 되돌릴 때는 모든 앱을 멈춘 뒤 카운터 값을 이 컬럼에 복사한다.
ALTER TABLE coupon_event
    MODIFY COLUMN next_acceptance_sequence BIGINT NOT NULL DEFAULT 0
        COMMENT 'deprecated by ADR-006: not updated; see coupon_event_admission_counter',
    ALGORITHM=INPLACE, LOCK=NONE;
