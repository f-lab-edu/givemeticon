package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponIssueRequestMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponIssueRequestServiceTest {

    @InjectMocks
    CouponIssueRequestService couponIssueRequestService;

    @Mock
    CouponIssueRequestMapper couponIssueRequestMapper;

    int userId = 1;
    int stockId = 100;

    @Test
    @DisplayName("처음 접수하는 (userId, stockId)는 새 행을 만들고 newlyAccepted=true를 반환한다.")
    void accept_firstTime_insertsNewRowAndReportsNewlyAccepted() {
        when(couponIssueRequestMapper.insertIgnore(any(CouponIssueRequest.class))).thenReturn(1);

        CouponIssueRequestService.AcceptResult result = couponIssueRequestService.accept(userId, stockId);

        assertThat(result.newlyAccepted()).isTrue();
        assertThat(result.request().getStatus()).isEqualTo(CouponRequestStatus.PENDING);
        verify(couponIssueRequestMapper, never()).findByUserIdAndStockId(anyInt(), anyInt());
    }

    @Test
    @DisplayName("이미 접수된 (userId, stockId)는 새 행을 만들지 않고 기존 기록을 반환한다 (재시도 멱등성).")
    void accept_alreadyAccepted_returnsExistingRowWithoutInsertingAgain() {
        CouponIssueRequest existing = CouponIssueRequest.builder()
                .userId(userId)
                .stockId(stockId)
                .status(CouponRequestStatus.ISSUED)
                .build();
        when(couponIssueRequestMapper.insertIgnore(any(CouponIssueRequest.class))).thenReturn(0);
        when(couponIssueRequestMapper.findByUserIdAndStockId(userId, stockId)).thenReturn(Optional.of(existing));

        CouponIssueRequestService.AcceptResult result = couponIssueRequestService.accept(userId, stockId);

        assertThat(result.newlyAccepted()).isFalse();
        assertThat(result.request()).isSameAs(existing);
    }

    @Test
    @DisplayName("발급 완료 처리는 매퍼에 요청 id와 쿠폰 id를 그대로 전달한다.")
    void markIssued_delegatesToMapper() {
        couponIssueRequestService.markIssued(42L, 7);

        verify(couponIssueRequestMapper).markIssued(42L, 7);
    }

    @Test
    @DisplayName("거절 처리는 매퍼에 요청 id와 사유를 그대로 전달한다.")
    void markRejected_delegatesToMapper() {
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);

        couponIssueRequestService.markRejected(42L, "NOT_ENOUGH_COUPON_STOCK");

        verify(couponIssueRequestMapper).markRejected(org.mockito.ArgumentMatchers.eq(42L), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo("NOT_ENOUGH_COUPON_STOCK");
    }
}
