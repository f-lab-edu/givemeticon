package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponErrorCode;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
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
    CouponStockService couponStockService;

    @Mock
    CouponIssueRequestService couponIssueRequestService;

    @Mock
    CouponMapper couponMapper;

    @Spy
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

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
        return pendingRequestBuilder().build();
    }

    private CouponIssueRequest.CouponIssueRequestBuilder pendingRequestBuilder() {
        return CouponIssueRequest.builder()
                .userId(userId)
                .stockId(stockId)
                .couponName(couponName)
                .couponType(CouponType.FREE_POINT)
                .price(price)
                .status(CouponRequestStatus.PENDING)
                .createdDate(java.time.LocalDateTime.now());
    }

    @Test
    @DisplayName("새로 접수된 요청은 재고 차감과 쿠폰 발급을 원자 처리한 뒤 접수 기록을 ISSUED로 남긴다.")
    void create_Coupon_NewlyAccepted_Success() {
        CouponIssueRequest request = pendingRequest();
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
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
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
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
        CouponIssueRequest issued = pendingRequestBuilder().status(CouponRequestStatus.ISSUED).build();
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenReturn(new CouponIssueRequestService.AcceptResult(issued, false));

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
        verify(couponIssueRequestService, never()).markIssued(anyInt(), anyInt());
    }

    @Test
    @DisplayName("[재시도 멱등성] 이미 거절된 요청은 같은 사유로 재발급을 시도하지 않고 동일한 예외를 던진다.")
    void create_Coupon_Retry_AlreadyRejected_ReturnsSameOutcome() {
        CouponIssueRequest rejected = pendingRequestBuilder().status(CouponRequestStatus.REJECTED)
                .reason(CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name()).build();
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenReturn(new CouponIssueRequestService.AcceptResult(rejected, false));

        assertThatThrownBy(() -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto))
                .isInstanceOf(NotEnoughCouponStockException.class);

        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("[장애 상태 확인] 이전 시도가 PENDING 상태로 남아있으면 결과를 확신할 수 없으므로 재시도 대신 확인 중 예외를 던진다.")
    void create_Coupon_Retry_StillPending_IsUncertain_ThrowsRequestPending() {
        CouponIssueRequest stillPending = pendingRequest();
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
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
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenReturn(new CouponIssueRequestService.AcceptResult(request, true));
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenThrow(new AlreadyIssuedCouponException());

        assertThatThrownBy(() -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto))
                .isInstanceOf(AlreadyIssuedCouponException.class);

        verify(couponIssueRequestService).markRejected(request.getId(), CouponErrorCode.COUPON_ALREADY_ISSUED.name());
    }

    // --- recoverPendingRequest (장애 복구) ---

    @Test
    @DisplayName("[장애 복구] 그 사이 이미 처리된 요청(더 이상 PENDING이 아님)은 손대지 않는다.")
    void recover_AlreadyResolved_DoesNothing() {
        CouponIssueRequest stale = pendingRequest();
        CouponIssueRequest nowIssued = pendingRequestBuilder().status(CouponRequestStatus.ISSUED).build();
        when(couponIssueRequestService.findById(stale.getId())).thenReturn(Optional.of(nowIssued));

        createCouponFacade.recoverPendingRequest(stale);

        verify(couponMapper, never()).findByUserIdAndStockId(anyInt(), anyInt());
        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("[장애 복구] 쿠폰이 이미 존재하면(재고차감+쿠폰생성은 성공, markIssued만 누락) 재고를 다시 건드리지 않고 기록만 채운다.")
    void recover_CouponAlreadyExists_BackfillsMarkIssuedWithoutTouchingStock() {
        CouponIssueRequest stale = pendingRequest();
        when(couponIssueRequestService.findById(stale.getId())).thenReturn(Optional.of(stale));
        Coupon existingCoupon = Coupon.builder()
                .userId(userId).stockId(stockId).name(couponName).couponType(CouponType.FREE_POINT)
                .couponNumber("ABC").price(price).build();
        setCouponId(existingCoupon, 777);
        when(couponMapper.findByUserIdAndStockId(userId, stockId)).thenReturn(Optional.of(existingCoupon));

        createCouponFacade.recoverPendingRequest(stale);

        verify(couponIssueRequestService).markIssued(stale.getId(), 777);
        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("[장애 복구] 쿠폰이 없으면(발급 트랜잭션이 실행/커밋된 적 없음) 접수 시점 값 그대로 발급을 다시 시도한다.")
    void recover_NoCouponYet_RetriesIssuanceWithStoredRequestDetails() {
        CouponIssueRequest stale = pendingRequest();
        when(couponIssueRequestService.findById(stale.getId())).thenReturn(Optional.of(stale));
        when(couponMapper.findByUserIdAndStockId(userId, stockId)).thenReturn(Optional.empty());
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price)).thenReturn(888);

        createCouponFacade.recoverPendingRequest(stale);

        verify(couponService).issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price);
        verify(couponIssueRequestService).markIssued(stale.getId(), 888);
    }

    @Test
    @DisplayName("[장애 복구] 쿠폰이 없고 재고도 소진됐다면 REJECTED로 정리한다.")
    void recover_NoCouponAndStockExhausted_MarksRejected() {
        CouponIssueRequest stale = pendingRequest();
        when(couponIssueRequestService.findById(stale.getId())).thenReturn(Optional.of(stale));
        when(couponMapper.findByUserIdAndStockId(userId, stockId)).thenReturn(Optional.empty());
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenThrow(new NotEnoughCouponStockException());

        assertThatThrownBy(() -> createCouponFacade.recoverPendingRequest(stale))
                .isInstanceOf(NotEnoughCouponStockException.class);

        verify(couponIssueRequestService).markRejected(stale.getId(), CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name());
    }

    // --- acceptOnly / processNextPendingForStock / getOwnRequest (접수 동기, 발급 비동기) ---

    @Test
    @DisplayName("[비동기 접수] 접수만 하고 발급은 시도하지 않는다.")
    void acceptOnly_DoesNotAttemptIssuance() {
        CouponIssueRequest request = pendingRequest();
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenReturn(new CouponIssueRequestService.AcceptResult(request, true));

        CouponIssueRequest result = createCouponFacade.acceptOnly(userId, createCouponRequestDto);

        assertThat(result).isEqualTo(request);
        assertThat(result.getStatus()).isEqualTo(CouponRequestStatus.PENDING);
        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("[비동기 접수] 이미 처리된 접수를 다시 접수해도 예외 없이 현재 상태를 그대로 반환한다.")
    void acceptOnly_Retry_ReturnsCurrentStateWithoutThrowing() {
        CouponIssueRequest issued = pendingRequestBuilder().status(CouponRequestStatus.ISSUED).build();
        when(couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenReturn(new CouponIssueRequestService.AcceptResult(issued, false));

        CouponIssueRequest result = createCouponFacade.acceptOnly(userId, createCouponRequestDto);

        assertThat(result.getStatus()).isEqualTo(CouponRequestStatus.ISSUED);
    }

    @Test
    @DisplayName("[비동기 워커] 처리할 PENDING이 없으면 false를 반환한다.")
    void processNextPendingForStock_NoPending_ReturnsFalse() {
        when(couponIssueRequestService.findOldestPendingByStockId(stockId)).thenReturn(Optional.empty());

        boolean result = createCouponFacade.processNextPendingForStock(stockId);

        assertThat(result).isFalse();
        verify(couponService, never()).issueCoupon(anyInt(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("[비동기 워커] 가장 오래된 PENDING 1건을 발급하고 true를 반환한다.")
    void processNextPendingForStock_IssuesOldestPending() {
        CouponIssueRequest oldest = pendingRequest();
        when(couponIssueRequestService.findOldestPendingByStockId(stockId)).thenReturn(Optional.of(oldest));
        when(couponIssueRequestService.findById(oldest.getId())).thenReturn(Optional.of(oldest));
        when(couponMapper.findByUserIdAndStockId(userId, stockId)).thenReturn(Optional.empty());
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price)).thenReturn(555);

        boolean result = createCouponFacade.processNextPendingForStock(stockId);

        assertThat(result).isTrue();
        verify(couponIssueRequestService).markIssued(oldest.getId(), 555);
    }

    @Test
    @DisplayName("[비동기 워커] 재고 소진 시 REJECTED로 기록한 뒤 예외를 그대로 던진다 - AopForTransaction이 커밋이 아니라 롤백하게 하기 위함(회귀: 안에서 삼키면 UnexpectedRollbackException).")
    void processNextPendingForStock_StockExhausted_MarksRejectedAndRethrows() {
        CouponIssueRequest oldest = pendingRequest();
        when(couponIssueRequestService.findOldestPendingByStockId(stockId)).thenReturn(Optional.of(oldest));
        when(couponIssueRequestService.findById(oldest.getId())).thenReturn(Optional.of(oldest));
        when(couponMapper.findByUserIdAndStockId(userId, stockId)).thenReturn(Optional.empty());
        when(couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price))
                .thenThrow(new NotEnoughCouponStockException());

        assertThatThrownBy(() -> createCouponFacade.processNextPendingForStock(stockId))
                .isInstanceOf(NotEnoughCouponStockException.class);

        verify(couponIssueRequestService).markRejected(oldest.getId(), CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name());
    }

    @Test
    @DisplayName("[상태 조회] 본인 접수는 그대로 반환한다.")
    void getOwnRequest_ReturnsRequest() {
        CouponIssueRequest request = pendingRequest();
        when(couponIssueRequestService.findById(request.getId())).thenReturn(Optional.of(request));

        CouponIssueRequest result = createCouponFacade.getOwnRequest(userId, request.getId());

        assertThat(result).isEqualTo(request);
    }

    @Test
    @DisplayName("[상태 조회] 존재하지 않는 접수 ID는 예외를 던진다.")
    void getOwnRequest_NotFound_Throws() {
        when(couponIssueRequestService.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> createCouponFacade.getOwnRequest(userId, 999L))
                .isInstanceOf(com.jinddung2.givemeticon.domain.coupon.exception.CouponRequestNotFoundException.class);
    }

    @Test
    @DisplayName("[상태 조회] 다른 회원의 접수 ID를 조회하면 예외를 던진다.")
    void getOwnRequest_OtherUsersRequest_Throws() {
        CouponIssueRequest othersRequest = CouponIssueRequest.builder()
                .userId(userId + 1).stockId(stockId).couponName(couponName).couponType(CouponType.FREE_POINT)
                .price(price).status(CouponRequestStatus.PENDING).createdDate(java.time.LocalDateTime.now()).build();
        when(couponIssueRequestService.findById(othersRequest.getId())).thenReturn(Optional.of(othersRequest));

        assertThatThrownBy(() -> createCouponFacade.getOwnRequest(userId, othersRequest.getId()))
                .isInstanceOf(com.jinddung2.givemeticon.domain.coupon.exception.CouponUserMismatchException.class);
    }

    // --- processBatchForStock (접수/발급 분리 + 묶음 차감) ---

    private CouponIssueRequest pendingRequestWithId(long id, int forUserId) {
        CouponIssueRequest request = CouponIssueRequest.builder()
                .userId(forUserId).stockId(stockId).couponName(couponName).couponType(CouponType.FREE_POINT)
                .price(price).status(CouponRequestStatus.PENDING).createdDate(java.time.LocalDateTime.now()).build();
        setRequestId(request, id);
        return request;
    }

    private Coupon couponWithId(int id, int forUserId) {
        Coupon coupon = Coupon.builder()
                .userId(forUserId).stockId(stockId).name(couponName).couponType(CouponType.FREE_POINT)
                .couponNumber("C" + id).price(price).build();
        setCouponId(coupon, id);
        return coupon;
    }

    @Test
    @DisplayName("[묶음 발급] 처리할 PENDING이 없으면 0을 반환하고 재고를 건드리지 않는다.")
    void processBatchForStock_NoPending_ReturnsZero() {
        when(couponIssueRequestService.findPendingBatch(stockId, 5)).thenReturn(List.of());

        int processed = createCouponFacade.processBatchForStock(stockId, 5);

        assertThat(processed).isZero();
        verify(couponStockService, never()).decreaseStockByIfEnough(anyInt(), anyInt());
    }

    @Test
    @DisplayName("[묶음 발급] 재고가 충분하면 batchSize 전체를 한 번에 ISSUED로 발급한다.")
    void processBatchForStock_EnoughStock_IssuesWholeBatch() {
        List<CouponIssueRequest> batch = IntStream.range(0, 3)
                .mapToObj(i -> pendingRequestWithId(100 + i, userId + i))
                .collect(Collectors.toList());
        when(couponIssueRequestService.findPendingBatch(stockId, 3)).thenReturn(batch);
        when(couponStockService.decreaseStockByIfEnough(stockId, 3)).thenReturn(true);
        List<Coupon> coupons = List.of(couponWithId(900, userId), couponWithId(901, userId + 1), couponWithId(902, userId + 2));
        when(couponService.issueCouponsBatch(stockId, batch)).thenReturn(coupons);

        int processed = createCouponFacade.processBatchForStock(stockId, 3);

        assertThat(processed).isEqualTo(3);
        verify(couponStockService, never()).getStockForUpdate(anyInt());
        verify(couponIssueRequestService, never()).markSoldOutBatch(any(), anyString());
        verify(couponIssueRequestService).markIssuedBatch(List.of(
                new CouponIssueRequestService.IssuedCoupon(100, 900),
                new CouponIssueRequestService.IssuedCoupon(101, 901),
                new CouponIssueRequestService.IssuedCoupon(102, 902)));
    }

    @Test
    @DisplayName("[묶음 발급] 재고가 batchSize보다 적으면 접수번호 앞쪽부터 잔여만큼만 ISSUED, 나머지는 SOLD_OUT이다.")
    void processBatchForStock_PartialStock_SplitsIssuedAndSoldOut() {
        List<CouponIssueRequest> batch = IntStream.range(0, 5)
                .mapToObj(i -> pendingRequestWithId(200 + i, userId + i))
                .collect(Collectors.toList());
        when(couponIssueRequestService.findPendingBatch(stockId, 5)).thenReturn(batch);
        when(couponStockService.decreaseStockByIfEnough(stockId, 5)).thenReturn(false);
        when(couponStockService.getStockForUpdate(stockId)).thenReturn(CouponStock.of(2));
        when(couponStockService.decreaseStockByIfEnough(stockId, 2)).thenReturn(true);
        List<CouponIssueRequest> toIssue = batch.subList(0, 2);
        List<Coupon> coupons = List.of(couponWithId(910, userId), couponWithId(911, userId + 1));
        when(couponService.issueCouponsBatch(stockId, toIssue)).thenReturn(coupons);

        int processed = createCouponFacade.processBatchForStock(stockId, 5);

        assertThat(processed).isEqualTo(5);
        verify(couponIssueRequestService).markIssuedBatch(List.of(
                new CouponIssueRequestService.IssuedCoupon(200, 910),
                new CouponIssueRequestService.IssuedCoupon(201, 911)));
        verify(couponIssueRequestService).markSoldOutBatch(
                List.of(202L, 203L, 204L), CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name());
    }

    @Test
    @DisplayName("[묶음 발급] 재고가 이미 0이면 이번 배치 전체를 SOLD_OUT으로 정리하고 쿠폰은 만들지 않는다.")
    void processBatchForStock_NoStockLeft_AllSoldOut() {
        List<CouponIssueRequest> batch = IntStream.range(0, 3)
                .mapToObj(i -> pendingRequestWithId(300 + i, userId + i))
                .collect(Collectors.toList());
        when(couponIssueRequestService.findPendingBatch(stockId, 3)).thenReturn(batch);
        when(couponStockService.decreaseStockByIfEnough(stockId, 3)).thenReturn(false);
        when(couponStockService.getStockForUpdate(stockId)).thenReturn(CouponStock.of(0));

        int processed = createCouponFacade.processBatchForStock(stockId, 3);

        assertThat(processed).isEqualTo(3);
        verify(couponService, never()).issueCouponsBatch(anyInt(), any());
        verify(couponIssueRequestService, never()).markIssuedBatch(any());
        verify(couponIssueRequestService).markSoldOutBatch(
                List.of(300L, 301L, 302L), CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name());
    }

    private void setRequestId(CouponIssueRequest request, long id) {
        try {
            java.lang.reflect.Field field = CouponIssueRequest.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(request, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setCouponId(Coupon coupon, int id) {
        try {
            java.lang.reflect.Field field = Coupon.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(coupon, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
