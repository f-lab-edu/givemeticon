package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyRedeemedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCoupon;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @InjectMocks
    CouponService sut;

    @Mock
    CouponMapper couponMapper;

    @Mock
    CouponStockMapper couponStockMapper;

    @Mock
    CertificationGenerator certificationGenerator;

    @Test
    @DisplayName("10000 포인트 주는 쿠폰을 생성하는데 성공한다.")
    void create_coupon_success() {
        int stockId = 1;
        String couponName = "테스트 선착순 쿠폰";
        int price = 10_000;
        int userId = 1;
        int couponLength = 16;

        String randomNum = "1234567812345678";
        when(couponMapper.saveIfNotIssued(any(Coupon.class))).thenReturn(1);
        when(certificationGenerator.createCouponNumber(couponLength)).thenReturn(randomNum);

        sut.createCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price);

        verify(couponMapper).saveIfNotIssued(any(Coupon.class));
        verify(certificationGenerator).createCouponNumber(couponLength);
    }

    @Test
    @DisplayName("동일 사용자와 재고의 쿠폰이 이미 발급된 경우 쿠폰 생성에 실패한다.")
    void create_coupon_fail_already_issued() {
        int stockId = 1;
        String couponName = "테스트 선착순 쿠폰";
        int price = 10_000;
        int userId = 1;
        int couponLength = 16;

        when(certificationGenerator.createCouponNumber(couponLength)).thenReturn("1234567812345678");
        when(couponMapper.saveIfNotIssued(any(Coupon.class))).thenReturn(0);

        assertThrows(AlreadyIssuedCouponException.class,
                () -> sut.createCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price));
    }

    @Test
    @DisplayName("issueCoupon: 재고 차감과 쿠폰 생성을 순서대로 수행해 성공하고, 생성된 쿠폰 id를 반환한다.")
    void issue_coupon_success() {
        int stockId = 1;
        String couponName = "테스트 선착순 쿠폰";
        int price = 10_000;
        int userId = 1;

        when(couponStockMapper.decreaseStockIfEnough(stockId)).thenReturn(1);
        when(certificationGenerator.createCouponNumber(16)).thenReturn("1234567812345678");
        when(couponMapper.saveIfNotIssued(any(Coupon.class))).thenAnswer(invocation -> {
            Coupon coupon = invocation.getArgument(0);
            setCouponId(coupon, 777);
            return 1;
        });

        int couponId = sut.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price);

        assertThat(couponId).isEqualTo(777);
        InOrder inOrder = inOrder(couponStockMapper, couponMapper);
        inOrder.verify(couponStockMapper).decreaseStockIfEnough(stockId);
        inOrder.verify(couponMapper).saveIfNotIssued(any(Coupon.class));
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

    @Test
    @DisplayName("issueCoupon: 재고가 없으면 쿠폰을 생성하지 않고 재고 부족 예외를 던진다.")
    void issue_coupon_fail_not_enough_stock() {
        int stockId = 1;
        String couponName = "테스트 선착순 쿠폰";
        int price = 10_000;
        int userId = 1;

        when(couponStockMapper.decreaseStockIfEnough(stockId)).thenReturn(0);

        assertThrows(NotEnoughCouponStockException.class,
                () -> sut.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price));

        verify(couponMapper, never()).saveIfNotIssued(any(Coupon.class));
    }

    @Test
    @DisplayName("issueCouponsBatch: 요청 목록만큼 쿠폰을 한 번에 만들어 반환한다.")
    void issue_coupons_batch_success() {
        int stockId = 1;
        String couponName = "테스트 선착순 쿠폰";
        int price = 10_000;

        List<CouponIssueRequest> requests = List.of(
                CouponIssueRequest.builder().userId(1).stockId(stockId).couponName(couponName)
                        .couponType(CouponType.FREE_POINT).price(price).status(CouponRequestStatus.PENDING)
                        .createdDate(LocalDateTime.now()).build(),
                CouponIssueRequest.builder().userId(2).stockId(stockId).couponName(couponName)
                        .couponType(CouponType.FREE_POINT).price(price).status(CouponRequestStatus.PENDING)
                        .createdDate(LocalDateTime.now()).build());
        when(certificationGenerator.createCouponNumber(16)).thenReturn("1234567812345678");
        when(couponMapper.saveAll(anyList())).thenReturn(2);

        List<Coupon> result = sut.issueCouponsBatch(stockId, requests);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Coupon::getUserId).containsExactly(1, 2);
        verify(couponMapper).saveAll(result);
        verify(couponStockMapper, never()).decreaseStockIfEnough(anyInt());
    }

    @Test
    @DisplayName("issueCouponsBatch: 삽입된 행 수가 요청 건수와 다르면 예외를 던진다.")
    void issue_coupons_batch_fail_row_count_mismatch() {
        int stockId = 1;
        List<CouponIssueRequest> requests = List.of(
                CouponIssueRequest.builder().userId(1).stockId(stockId).couponName("쿠폰")
                        .couponType(CouponType.FREE_POINT).price(1000).status(CouponRequestStatus.PENDING)
                        .createdDate(LocalDateTime.now()).build());
        when(certificationGenerator.createCouponNumber(16)).thenReturn("1234567812345678");
        when(couponMapper.saveAll(anyList())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> sut.issueCouponsBatch(stockId, requests));
    }

    @Test
    @DisplayName("쿠폰을 사용하면 조건부 업데이트가 성공한다.")
    void use_coupon_success(){
        int userId = 1;
        Coupon coupon = Coupon.builder()
                .userId(userId)
                .name("testCoupon")
                .couponNumber("COUPON123")
                .couponType(CouponType.FREE_POINT)
                .isUsed(false)
                .expiredDate(LocalDate.now().plusDays(1))
                .build();
        when(couponMapper.updateUsedIfUnused(coupon.getId())).thenReturn(1);

        sut.useCoupon(coupon);

        verify(couponMapper).updateUsedIfUnused(coupon.getId());
        verify(couponMapper, never()).merge(coupon);
    }

    @Test
    @DisplayName("조건부 업데이트가 실패하면 이미 사용한 쿠폰으로 처리한다.")
    void use_coupon_fail_already_redeemed() {
        Coupon coupon = Coupon.builder()
                .userId(1)
                .name("testCoupon")
                .couponNumber("COUPON123")
                .couponType(CouponType.FREE_POINT)
                .isUsed(false)
                .expiredDate(LocalDate.now().plusDays(1))
                .build();
        when(couponMapper.updateUsedIfUnused(coupon.getId())).thenReturn(0);

        assertThrows(AlreadyRedeemedCouponException.class,
                () -> sut.useCoupon(coupon));
    }

    @Test
    @DisplayName("쿠폰 넘버로 쿠폰 데이터를 찾는다.")
    void get_coupon_success(){
        int userId = 1;
        String couponNumber = "COUPON123";
        Coupon coupon = Coupon.builder()
                .userId(userId)
                .name("testCoupon")
                .couponNumber(couponNumber)
                .couponType(CouponType.FREE_POINT)
                .isUsed(false)
                .expiredDate(LocalDate.now().plusDays(1))
                .build();

        when(couponMapper.getCouponByCouponNumber(couponNumber)).thenReturn(Optional.of(coupon));
        Coupon result = sut.getCoupon(couponNumber);

        verify(couponMapper).getCouponByCouponNumber(couponNumber);
        assertThat(result).isEqualTo(coupon);
    }

    @Test
    @DisplayName("쿠폰 넘버에 해당하는 쿠폰이 존재하지 않아 NotFoundCoupon 예외가 발생한다.")
    void get_coupon_fail_notFoundCoupon(){
        String couponNumber = "COUPON123";

        when(couponMapper.getCouponByCouponNumber(couponNumber)).thenReturn(Optional.empty());

        assertThrows(NotFoundCoupon.class,
                () -> sut.getCoupon(couponNumber));
    }
}
