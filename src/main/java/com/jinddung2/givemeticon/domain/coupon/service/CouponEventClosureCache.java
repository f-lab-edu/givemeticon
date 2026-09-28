package com.jinddung2.givemeticon.domain.coupon.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종료(CLOSED)가 실제로 관측된 행사 ID를 기억하는 프로세스-로컬 캐시다("종료된 행사 빠른 응답" 전용).
 *
 * 이 캐시 자체는 정합성의 근거가 아니다 - 오직 접수 트랜잭션(행사 행 잠금 후 재검증)이 실제로
 * CLOSED를 읽어냈을 때만 채워진다({@link #markClosed}). CLOSED는 터미널 상태라 다시 OPEN으로
 * 돌아가지 않으므로, 한 번 true로 기록된 뒤에는 영원히 유효하다 - 무효화(만료·삭제)가 필요 없다.
 *
 * 캐시가 비어 있어도(앱 재시작 직후, 아직 이 인스턴스가 그 행사의 종료를 발견한 적이 없는 경우)
 * 안전하다 - 그 경우 요청은 그대로 기존 잠금 트랜잭션(또는 묶음 큐) 경로로 가서 같은 결과(거절)를
 * 받고, 그 과정에서 이 캐시가 채워진다. 즉 "행사당 회원 신청 여부를 매 요청마다 DB에 다시 물어보지
 * 않고, 실제로 종료를 확인한 적이 있을 때만 빠르게 응답한다"는 지연 발견(lazy discovery) 전략이다 -
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
}
