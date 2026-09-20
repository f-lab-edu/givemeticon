package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponIssueRequestMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CouponIssueRequestService {

    private final CouponIssueRequestMapper couponIssueRequestMapper;
    private final MeterRegistry meterRegistry;

    /**
     * (userId, stockId)에 대한 접수를 시도한다. 처음 접수라면 새 행이 만들어지고 그 삽입
     * 순서가 이 재고에 대한 접수 순서가 된다 (newlyAccepted=true). 이미 접수된 적이 있다면
     * 새 행을 만들지 않고 기존 기록을 그대로 반환한다 (newlyAccepted=false) - 재시도를
     * 멱등하게 만드는 지점이다. 쿠폰 이름/유형/가격을 함께 저장해두는 이유는, 장애로 이
     * 접수가 PENDING인 채 멈췄을 때 복구 배치가 원래 요청과 동일한 쿠폰을 재발급할 수
     * 있어야 하기 때문이다.
     *
     * 1단계 진단에서 "Hikari 커넥션 점유 39ms"가 무엇으로 이뤄졌는지 더 쪼개기 위해
     * INSERT 실행 시간·커밋 시간(TransactionSynchronization)·(재시도일 때만 타는)
     * 검증 쿼리 시간을 각각 타이머로 남긴다 - 동작은 바꾸지 않는다(순수 계측 추가).
     */
    @Transactional
    public AcceptResult accept(int userId, int stockId, String couponName, CouponType couponType, int price) {
        registerCommitTimer();
        CouponIssueRequest newRequest = CouponIssueRequest.pending(userId, stockId, couponName, couponType, price);
        int insertedRows = timed("coupon.accept.insert", () -> couponIssueRequestMapper.insertIgnore(newRequest));
        if (insertedRows == 1) {
            return new AcceptResult(newRequest, true);
        }

        CouponIssueRequest existing = timed("coupon.accept.verify_query",
                        () -> couponIssueRequestMapper.findByUserIdAndStockId(userId, stockId))
                .orElseThrow(() -> new IllegalStateException(
                        "coupon_issue_request insert was ignored but no existing row was found for userId=" + userId + ", stockId=" + stockId));
        return new AcceptResult(existing, false);
    }

    private <T> T timed(String metricName, java.util.function.Supplier<T> body) {
        return Timer.builder(metricName)
                .description("coupon.accept 내부 구간별 실행 시간(1단계 진단용 계측)")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(body);
    }

    /**
     * 트랜잭션 커밋 자체(플러시+fsync 등 물리 커밋)에 걸린 시간만 따로 잰다. INSERT 실행
     * 시간과는 구간이 겹치지 않는다 - beforeCommit은 애플리케이션 로직이 끝난 뒤, 실제
     * 커밋 직전에 콜백된다.
     */
    private void registerCommitTimer() {
        // 실제 트랜잭션 동기화가 활성화돼 있을 때만 등록한다 - 순수 Mockito 단위 테스트처럼
        // 스프링 트랜잭션 없이 이 메서드를 직접 부르는 경우 registerSynchronization 자체가
        // IllegalStateException을 던지므로, 그런 호출까지 계측이 막지 않게 가드를 둔다.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            long beforeCommitNanos;

            @Override
            public void beforeCommit(boolean readOnly) {
                beforeCommitNanos = System.nanoTime();
            }

            @Override
            public void afterCommit() {
                if (beforeCommitNanos == 0) {
                    return;
                }
                Timer.builder("coupon.accept.commit")
                        .description("coupon.accept 내부 구간별 실행 시간(1단계 진단용 계측)")
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                        .record(java.time.Duration.ofNanos(System.nanoTime() - beforeCommitNanos));
            }
        });
    }

    public Optional<CouponIssueRequest> findById(long requestId) {
        return couponIssueRequestMapper.findById(requestId);
    }

    /**
     * status가 PENDING인 채로 updatedDate가 olderThanMinutes분보다 오래된 접수 목록.
     * 정상적인 처리라면 락 대기·발급 트랜잭션이 초 단위로 끝나므로, 이 목록에 남아있다는
     * 것은 장애(프로세스 종료 등)로 결과가 확정되지 못했다는 뜻이다.
     */
    public List<CouponIssueRequest> findStalePending(long olderThanMinutes) {
        return couponIssueRequestMapper.findStalePending(olderThanMinutes);
    }

    /**
     * 비동기 발급 워커가 이 재고(stockId)에서 다음으로 처리할 접수 1건을 고른다. id ASC로만
     * 골라야 같은 행사 안에서 접수 순서가 지켜진다 - 호출자(CreateCouponFacade)가 재고별
     * 분산 락을 쥔 채로 불러야, 두 앱 인스턴스가 동시에 "다음 건"을 서로 다르게 고르지 않는다.
     */
    public Optional<CouponIssueRequest> findOldestPendingByStockId(int stockId) {
        return couponIssueRequestMapper.findOldestPendingByStockId(stockId);
    }

    /** 지금 PENDING 건이 남아있는 재고(stockId) 목록. 비동기 워커가 매 폴링마다 훑는다. */
    public List<Integer> findDistinctPendingStockIds() {
        return couponIssueRequestMapper.findDistinctPendingStockIds();
    }

    /** 묶음 발급 워커가 이 재고에서 다음으로 처리할 접수를 접수번호 오름차순으로 최대 limit건 고른다. */
    public List<CouponIssueRequest> findPendingBatch(int stockId, int limit) {
        return couponIssueRequestMapper.findPendingBatch(stockId, limit);
    }

    @Transactional
    public void markIssued(long requestId, int couponId) {
        couponIssueRequestMapper.markIssued(requestId, couponId);
    }

    @Transactional
    public void markRejected(long requestId, String reason) {
        couponIssueRequestMapper.markRejected(requestId, reason);
    }

    /** requestId -> couponId 매핑을 한 번의 UPDATE로 반영한다. */
    @Transactional
    public void markIssuedBatch(List<IssuedCoupon> issuedCoupons) {
        List<Map<String, Object>> items = issuedCoupons.stream()
                .map(issued -> Map.<String, Object>of("requestId", issued.requestId(), "couponId", issued.couponId()))
                .toList();
        couponIssueRequestMapper.markIssuedBatch(items);
    }

    @Transactional
    public void markSoldOutBatch(List<Long> requestIds, String reason) {
        couponIssueRequestMapper.markSoldOutBatch(requestIds, reason);
    }

    public record AcceptResult(CouponIssueRequest request, boolean newlyAccepted) {
    }

    public record IssuedCoupon(long requestId, int couponId) {
    }
}
