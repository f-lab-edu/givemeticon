package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.domain.CashPointEarnHistory;
import com.jinddung2.givemeticon.domain.point.exception.NotEnoughCashPointException;
import com.jinddung2.givemeticon.domain.point.exception.NotFoundCashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointEarnHistoryMapper;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import com.jinddung2.givemeticon.domain.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashCashPointServiceTest {
    @InjectMocks
    CashPointService sut;
    @Mock
    CashPointMapper cashPointMapper;
    @Mock
    CashPointEarnHistoryMapper cashPointEarnHistoryMapper;

    int cashPointId = 1;

    private Coupon couponIssuedOn(LocalDate createdDate) {
        Coupon coupon = Coupon.builder()
                .userId(1)
                .name("testCoupon")
                .couponNumber("COUPON123")
                .couponType(CouponType.FREE_POINT)
                .createdDate(createdDate)
                .build();
        setCouponId(coupon, 55);
        return coupon;
    }

    private void setCouponId(Coupon coupon, int id) {
        try {
            Field field = Coupon.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(coupon, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CashPointEarnHistory batch(long id, int remainingAmount, LocalDate earnedDate) {
        CashPointEarnHistory history = CashPointEarnHistory.builder()
                .cashPointId(cashPointId)
                .couponId((int) id)
                .amount(remainingAmount)
                .earnedDate(earnedDate)
                .expiredDate(earnedDate.plusMonths(1))
                .build();
        try {
            Field field = CashPointEarnHistory.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(history, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return history;
    }

    @Test
    @DisplayName("회원가입할 때 기본 포인트도 적립한다.")
    void save_default_point(){
        int pointId = 1;
        when(cashPointMapper.save(any(CashPoint.class))).thenReturn(pointId);

        sut.createPoint();

        verify(cashPointMapper).save(any(CashPoint.class));
    }

    @Test
    @DisplayName("id를 통해 캐시포인트를 찾으면 캐시포인트 객체를 반환한다.")
    void when_createPoint_should_be_cash_point(){
        int pointId = 1, defaultPoint = 1000;
        CashPoint cashPoint = CashPoint.builder()
                .id(pointId)
                .cashPoint(defaultPoint) // Assuming DEFAULT_POINT is accessible here; otherwise, use the actual point value.
                .build();
        when(cashPointMapper.findById(pointId)).thenReturn(Optional.of(cashPoint));

        sut.getCashPoint(pointId);

        verify(cashPointMapper).findById(pointId);
    }

    @Test
    @DisplayName("id를 통해 캐시포인트를 찾을 때 존재하지 않으면 NotFoundCashPoint 예외를 발생시킨다.")
    void when_getCashPoint_with_nonexistent_id_should_throw_NotFoundCashPoint_exception(){
        int pointId = 1;
        when(cashPointMapper.findById(pointId)).thenReturn(Optional.empty());

        assertThrows(NotFoundCashPoint.class,
                () -> sut.getCashPoint(pointId));
    }

    @Test
    @DisplayName("발급 후 7일 이내 사용이면 1만 포인트를 적립하고, 적립 기록은 사용일로부터 1개월 뒤 만료로 남는다.")
    void addPointForCouponRedeem_withinSevenDays_earnsFixedAmount() {
        User user = User.builder().cashPointId(cashPointId).build();
        LocalDate issuedDate = LocalDate.of(2026, 1, 1);
        LocalDate redeemedDate = issuedDate.plusDays(7); // 경계값(7일째)도 적립 대상
        Coupon coupon = couponIssuedOn(issuedDate);
        when(cashPointEarnHistoryMapper.insertIgnore(any(CashPointEarnHistory.class))).thenReturn(1);
        when(cashPointMapper.incrementCashPoint(cashPointId, 10_000)).thenReturn(1);

        sut.addPointForCouponRedeem(user, coupon, redeemedDate);

        ArgumentCaptor<CashPointEarnHistory> captor = ArgumentCaptor.forClass(CashPointEarnHistory.class);
        verify(cashPointEarnHistoryMapper).insertIgnore(captor.capture());
        CashPointEarnHistory saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getCouponId()).isEqualTo(coupon.getId());
        org.assertj.core.api.Assertions.assertThat(saved.getAmount()).isEqualTo(10_000);
        org.assertj.core.api.Assertions.assertThat(saved.getEarnedDate()).isEqualTo(redeemedDate);
        org.assertj.core.api.Assertions.assertThat(saved.getExpiredDate()).isEqualTo(redeemedDate.plusMonths(1));
        verify(cashPointMapper).incrementCashPoint(cashPointId, 10_000);
    }

    @Test
    @DisplayName("발급 후 7일이 지나 사용하면 적립하지 않는다 (쿠폰 사용 자체는 별개라 예외를 던지지 않는다).")
    void addPointForCouponRedeem_afterSevenDays_doesNotEarn() {
        User user = User.builder().cashPointId(cashPointId).build();
        LocalDate issuedDate = LocalDate.of(2026, 1, 1);
        LocalDate redeemedDate = issuedDate.plusDays(8);
        Coupon coupon = couponIssuedOn(issuedDate);

        sut.addPointForCouponRedeem(user, coupon, redeemedDate);

        verify(cashPointEarnHistoryMapper, never()).insertIgnore(any(CashPointEarnHistory.class));
        verify(cashPointMapper, never()).incrementCashPoint(anyInt(), anyInt());
    }

    @Test
    @DisplayName("같은 쿠폰으로 이미 적립된 적이 있으면(유니크 제약 위반) 다시 적립하지 않는다 - 재시도에도 멱등.")
    void addPointForCouponRedeem_alreadyEarnedForThisCoupon_isIdempotent() {
        User user = User.builder().cashPointId(cashPointId).build();
        LocalDate issuedDate = LocalDate.of(2026, 1, 1);
        Coupon coupon = couponIssuedOn(issuedDate);
        when(cashPointEarnHistoryMapper.insertIgnore(any(CashPointEarnHistory.class))).thenReturn(0);

        sut.addPointForCouponRedeem(user, coupon, issuedDate);

        verify(cashPointMapper, never()).incrementCashPoint(anyInt(), anyInt());
    }

    @Test
    @DisplayName("적립 기록은 성공했는데 잔액 증가 대상이 없으면 NotFoundCashPoint 예외를 발생시킨다.")
    void addPointForCouponRedeem_fail_not_found_cash_point() {
        User user = User.builder().cashPointId(cashPointId).build();
        LocalDate issuedDate = LocalDate.of(2026, 1, 1);
        Coupon coupon = couponIssuedOn(issuedDate);
        when(cashPointEarnHistoryMapper.insertIgnore(any(CashPointEarnHistory.class))).thenReturn(1);
        when(cashPointMapper.incrementCashPoint(cashPointId, 10_000)).thenReturn(0);

        assertThrows(NotFoundCashPoint.class,
                () -> sut.addPointForCouponRedeem(user, coupon, issuedDate));
    }

    @Test
    @DisplayName("사용 가능한 적립 건 하나로 충분하면 그 건과 잔액만 정확히 줄어든다.")
    void spendPoint_singleBatchSufficient() {
        User user = User.builder().cashPointId(cashPointId).build();
        CashPointEarnHistory b1 = batch(1L, 5_000, LocalDate.of(2026, 1, 1));
        when(cashPointEarnHistoryMapper.findSpendableBatches(eq(cashPointId), any(LocalDate.class)))
                .thenReturn(List.of(b1));
        when(cashPointEarnHistoryMapper.decreaseRemainingAmount(1L, 3_000)).thenReturn(1);
        when(cashPointMapper.decreaseCashPoint(cashPointId, 3_000)).thenReturn(1);

        sut.spendPoint(user, 3_000, LocalDate.now());

        verify(cashPointEarnHistoryMapper).decreaseRemainingAmount(1L, 3_000);
        verify(cashPointMapper).decreaseCashPoint(cashPointId, 3_000);
    }

    @Test
    @DisplayName("한 건으로 부족하면 먼저 적립된(=먼저 만료되는) 건부터 순서대로 소진한다 (FIFO).")
    void spendPoint_spansMultipleBatches_oldestFirst() {
        User user = User.builder().cashPointId(cashPointId).build();
        CashPointEarnHistory older = batch(1L, 2_000, LocalDate.of(2026, 1, 1));
        CashPointEarnHistory newer = batch(2L, 5_000, LocalDate.of(2026, 1, 10));
        when(cashPointEarnHistoryMapper.findSpendableBatches(eq(cashPointId), any(LocalDate.class)))
                .thenReturn(List.of(older, newer)); // 매퍼가 이미 earned_date ASC로 정렬해 반환
        when(cashPointEarnHistoryMapper.decreaseRemainingAmount(1L, 2_000)).thenReturn(1);
        when(cashPointEarnHistoryMapper.decreaseRemainingAmount(2L, 3_000)).thenReturn(1);
        when(cashPointMapper.decreaseCashPoint(cashPointId, 5_000)).thenReturn(1);

        sut.spendPoint(user, 5_000, LocalDate.now());

        InOrder inOrder = inOrder(cashPointEarnHistoryMapper);
        inOrder.verify(cashPointEarnHistoryMapper).decreaseRemainingAmount(1L, 2_000);
        inOrder.verify(cashPointEarnHistoryMapper).decreaseRemainingAmount(2L, 3_000);
    }

    @Test
    @DisplayName("유효한 적립 합계가 요청 금액보다 적으면 잔액은 건드리지 않고 예외를 던진다.")
    void spendPoint_insufficientValidPoints_doesNotTouchBalance() {
        User user = User.builder().cashPointId(cashPointId).build();
        CashPointEarnHistory only = batch(1L, 2_000, LocalDate.of(2026, 1, 1));
        when(cashPointEarnHistoryMapper.findSpendableBatches(eq(cashPointId), any(LocalDate.class)))
                .thenReturn(List.of(only));
        when(cashPointEarnHistoryMapper.decreaseRemainingAmount(1L, 2_000)).thenReturn(1);

        assertThrows(NotEnoughCashPointException.class,
                () -> sut.spendPoint(user, 5_000, LocalDate.now()));

        verify(cashPointMapper, never()).decreaseCashPoint(anyInt(), anyInt());
    }

    @Test
    @DisplayName("다른 요청이 먼저 소진시킨 건(affected rows=0)은 건너뛰고 다음 건에서 채운다.")
    void spendPoint_raceOnBatch_skipsToNextBatch() {
        User user = User.builder().cashPointId(cashPointId).build();
        CashPointEarnHistory raced = batch(1L, 2_000, LocalDate.of(2026, 1, 1));
        CashPointEarnHistory next = batch(2L, 5_000, LocalDate.of(2026, 1, 10));
        when(cashPointEarnHistoryMapper.findSpendableBatches(eq(cashPointId), any(LocalDate.class)))
                .thenReturn(List.of(raced, next));
        when(cashPointEarnHistoryMapper.decreaseRemainingAmount(1L, 2_000)).thenReturn(0); // 이미 소진됨
        when(cashPointEarnHistoryMapper.decreaseRemainingAmount(2L, 3_000)).thenReturn(1);
        when(cashPointMapper.decreaseCashPoint(cashPointId, 3_000)).thenReturn(1);

        sut.spendPoint(user, 3_000, LocalDate.now());

        verify(cashPointMapper).decreaseCashPoint(cashPointId, 3_000);
    }

    @Test
    @DisplayName("0 이하의 포인트를 사용하려 하면 예외를 던진다.")
    void spendPoint_nonPositiveAmount_throws() {
        User user = User.builder().cashPointId(cashPointId).build();

        assertThrows(IllegalArgumentException.class,
                () -> sut.spendPoint(user, 0, LocalDate.now()));

        verifyNoInteractions(cashPointEarnHistoryMapper);
    }
}
