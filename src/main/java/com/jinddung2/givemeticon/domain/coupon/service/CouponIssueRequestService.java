package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponIssueRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponIssueRequestService {

    private final CouponIssueRequestMapper couponIssueRequestMapper;

    /**
     * (userId, stockId)에 대한 접수를 시도한다. 처음 접수라면 새 행이 만들어지고 그 삽입
     * 순서가 이 재고에 대한 접수 순서가 된다 (newlyAccepted=true). 이미 접수된 적이 있다면
     * 새 행을 만들지 않고 기존 기록을 그대로 반환한다 (newlyAccepted=false) - 재시도를
     * 멱등하게 만드는 지점이다.
     */
    @Transactional
    public AcceptResult accept(int userId, int stockId) {
        CouponIssueRequest newRequest = CouponIssueRequest.pending(userId, stockId);
        int insertedRows = couponIssueRequestMapper.insertIgnore(newRequest);
        if (insertedRows == 1) {
            return new AcceptResult(newRequest, true);
        }

        CouponIssueRequest existing = couponIssueRequestMapper.findByUserIdAndStockId(userId, stockId)
                .orElseThrow(() -> new IllegalStateException(
                        "coupon_issue_request insert was ignored but no existing row was found for userId=" + userId + ", stockId=" + stockId));
        return new AcceptResult(existing, false);
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
