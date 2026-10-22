package com.jinddung2.givemeticon.domain.coupon.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V1 접수가 마감된 행사 ID를 기억하는 프로세스-로컬 캐시다("종료된 행사 빠른 응답" 전용).
 *
 * 마감은 접수가 커밋된 카운터 값으로 확인한 것이다(ADR-006 D1): 접수 순번이 total_quantity에 도달했거나(그 시점의
 * coupon_event는 아직 OPEN일 수 있다), coupon_event가 CLOSED로 읽혔다. 둘 다 이후 V1 신청이 새로 커밋될 수 없는 상태라
 * 한 번 true로 기록된 뒤에는 영원히 유효하다 - 무효화(만료·삭제)가 필요 없다. 이 캐시 자체는 정합성의 근거가 아니다.
 * 기록은 마감을 커밋된 값으로 관측한 경로({@link #markClosed})이거나, 마지막 순번을 배정한 트랜잭션의 커밋 뒤
 * ({@link #markClosedAfterCommit})뿐이다. 커밋 전에 기록하면 롤백 시 캐시가 거짓이 된다.
 *
 * 캐시가 비어 있어도(앱 재시작 직후, 아직 이 인스턴스가 그 행사의 마감을 발견한 적이 없는 경우)
 * 안전하다 - 그 경우 요청은 그대로 기존 잠금 트랜잭션(또는 묶음 큐) 경로로 가서 같은 결과(거절)를
 * 받고, 그 과정에서 이 캐시가 채워진다. 즉 "행사당 회원 신청 여부를 매 요청마다 DB에 다시 물어보지
 * 않고, 실제로 마감을 확인한 적이 있을 때만 빠르게 응답한다"는 지연 발견(lazy discovery) 전략이다 -
 * 매 신규 접수 요청마다 별도 조회를 추가하면(예: 락 없는 SELECT를 모든 요청에 얹으면) 진행 중인
 * 행사에서도 커넥션 풀 부담이 늘어 오히려 접수 자체가 느려진다(부하 검증에서 실측됨).
 */
@Component
public class CouponEventClosureCache {

    private final Set<Long> closedEventIds = ConcurrentHashMap.newKeySet();

    public boolean isKnownClosed(long eventId) {
        return closedEventIds.contains(eventId);
    }

    public void markClosed(long eventId) {
        closedEventIds.add(eventId);
    }

    /**
     * 현재 트랜잭션이 커밋된 뒤에만 기록한다. 트랜잭션이 롤백되면 기록하지 않는다. 트랜잭션 동기화가 없으면
     * (트랜잭션 밖 호출) 문장 단위로 커밋되므로 바로 기록한다.
     */
    public void markClosedAfterCommit(long eventId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            markClosed(eventId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                markClosed(eventId);
            }
        });
    }
}
