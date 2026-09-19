package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponErrorCode;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateCouponFacadeTest {

    @InjectMocks
    CreateCouponFacade createCouponFacade;

    @Mock
    CouponService couponService;

    @Mock
    CouponIssueRequestService couponIssueRequestService;

    CreateCouponRequestDto createCouponRequestDto;

    int stockId = 1;
    String couponName = "테스트 선착순 쿠폰";
    int price = 10_000;
    int userId = 1;

    @BeforeEach
    void setUp() {
        createCouponRequestDto = new CreateCouponRequestDto(stockId, couponName, CouponType.FREE_POINT, price);
    }

    private CouponIssueRequest pendingRequest() {
        CouponIssueRequest request = CouponIssueRequest.builder()
                .userId(userId)
                .stockId(stockId)
                .status(CouponRequestStatus.PENDING)
                .build();
        return request;
    }

    @Test
    @DisplayName("새로 접수된 요청은 재고 차감과 쿠폰 발급을 원자 처리한 뒤 접수 기록을 ISSUED로 남긴다.")
    void create_Coupon_NewlyAccepted_Success() {
        CouponIssueRequest request = pendingRequest();
        when(couponIssueRequestService.accept(userId, stockId))
                .thenReturn(new CouponIssueRequestService.AcceptResult(request, true));
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price)).thenReturn(999);

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        verify(couponService).issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price);
        verify(couponIssueRequestService).markIssued(request.getId(), 999);
        verify(couponIssueRequestService, never()).markRejected(anyInt(), anyString());
    }

    @Test
    @DisplayName("새로 접수됐지만 재고가 없으면 접수 기록을 REJECTED로 남기고 예외를 전파한다.")
    void create_Coupon_NewlyAccepted_NotEnoughStock() {
        CouponIssueRequest request = pendingRequest();
        when(couponIssueRequestService.accept(userId, stockId))
                .thenReturn(new CouponIssueRequestService.AcceptResult(request, true));
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenThrow(new NotEnoughCouponStockException());

        assertThatThrownBy(() -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto))
                .isInstanceOf(NotEnoughCouponStockException.class);

        verify(couponIssueRequestService).markRejected(request.getId(), CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name());
    }

    @Test
    @DisplayName("[재시도 멱등성] 이미 발급 완료된 요청은 재발급을 시도하지 않고 조용히 성공 처리한다.")
    void create_Coupon_Retry_AlreadyIssued_IsIdempotent() {
        CouponIssueRequest issued = CouponIssueRequest.builder()
                .userId(userId).stockId(stockId).status(CouponRequestStatus.ISSUED).build();
        when(couponIssueRequestService.accept(userId, stockId))
                .thenReturn(new CouponIssueRequestService.AcceptResult(issued, false));

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
        verify(couponIssueRequestService, never()).markIssued(anyInt(), anyInt());
    }

    @Test
    @DisplayName("[재시도 멱등성] 이미 거절된 요청은 같은 사유로 재발급을 시도하지 않고 동일한 예외를 던진다.")
    void create_Coupon_Retry_AlreadyRejected_ReturnsSameOutcome() {
        CouponIssueRequest rejected = CouponIssueRequest.builder()
                .userId(userId).stockId(stockId).status(CouponRequestStatus.REJECTED)
                .reason(CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name()).build();
        when(couponIssueRequestService.accept(userId, stockId))
                .thenReturn(new CouponIssueRequestService.AcceptResult(rejected, false));

        assertThatThrownBy(() -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto))
                .isInstanceOf(NotEnoughCouponStockException.class);

        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("[장애 상태 확인] 이전 시도가 PENDING 상태로 남아있으면 결과를 확신할 수 없으므로 재시도 대신 확인 중 예외를 던진다.")
    void create_Coupon_Retry_StillPending_IsUncertain_ThrowsRequestPending() {
        CouponIssueRequest stillPending = pendingRequest();
        when(couponIssueRequestService.accept(userId, stockId))
                .thenReturn(new CouponIssueRequestService.AcceptResult(stillPending, false));

        assertThatThrownBy(() -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto))
                .isInstanceOf(GiveMeTiConException.class)
                .extracting(e -> ((GiveMeTiConException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_REQUEST_PENDING);

        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("새로 접수됐지만 쿠폰이 이미 존재하면 접수 기록을 REJECTED로 남기고 예외를 전파한다.")
    void create_Coupon_NewlyAccepted_AlreadyIssuedAtCouponInsert() {
        CouponIssueRequest request = pendingRequest();
        when(couponIssueRequestService.accept(userId, stockId))
                .thenReturn(new CouponIssueRequestService.AcceptResult(request, true));
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenThrow(new AlreadyIssuedCouponException());

        assertThatThrownBy(() -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto))
                .isInstanceOf(AlreadyIssuedCouponException.class);

        verify(couponIssueRequestService).markRejected(request.getId(), CouponErrorCode.COUPON_ALREADY_ISSUED.name());
    }
}
