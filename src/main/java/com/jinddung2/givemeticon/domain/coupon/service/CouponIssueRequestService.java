package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponIssueRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CouponIssueRequestService {

    private final CouponIssueRequestMapper couponIssueRequestMapper;

    /**
     * (userId, stockId)에 대한 접수를 시도한다. 처음 접수라면 새 행이 만들어지고 그 삽입
     * 순서가 이 재고에 대한 접수 순서가 된다 (newlyAccepted=true). 이미 접수된 적이 있다면
     * 새 행을 만들지 않고 기존 기록을 그대로 반환한다 (newlyAccepted=false) - 재시도를
     * 멱등하게 만드는 지점이다. 쿠폰 이름/유형/가격을 함께 저장해두는 이유는, 장애로 이
     * 접수가 PENDING인 채 멈췄을 때 복구 배치가 원래 요청과 동일한 쿠폰을 재발급할 수
     * 있어야 하기 때문이다.
     */
    @Transactional
    public AcceptResult accept(int userId, int stockId, String couponName, CouponType couponType, int price) {
        CouponIssueRequest newRequest = CouponIssueRequest.pending(userId, stockId, couponName, couponType, price);
        int insertedRows = couponIssueRequestMapper.insertIgnore(newRequest);
        if (insertedRows == 1) {
            return new AcceptResult(newRequest, true);
        }

        CouponIssueRequest existing = couponIssueRequestMapper.findByUserIdAndStockId(userId, stockId)
                .orElseThrow(() -> new IllegalStateException(
                        "coupon_issue_request insert was ignored but no existing row was found for userId=" + userId + ", stockId=" + stockId));
        return new AcceptResult(existing, false);
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

    @Transactional
    public void markIssued(long requestId, int couponId) {
        couponIssueRequestMapper.markIssued(requestId, couponId);
    }

    @Transactional
    public void markRejected(long requestId, String reason) {
        couponIssueRequestMapper.markRejected(requestId, reason);
    }

    public record AcceptResult(CouponIssueRequest request, boolean newlyAccepted) {
    }
}
