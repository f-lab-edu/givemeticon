package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyRedeemedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCoupon;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponMapper couponMapper;
    private final CouponStockMapper couponStockMapper;
    private final CertificationGenerator certificationGenerator;

    /**
     * 재고 차감과 쿠폰 생성을 하나의 트랜잭션으로 묶어 원자적으로 처리한다. 둘 중 하나라도
     * 실패하면 나머지도 함께 롤백되어, 재고만 줄고 쿠폰은 없는 상태가 영구히 남지 않는다.
     */
    @Transactional
    public int issueCoupon(int userId, int stockId, String couponName, CouponType couponType, int price) {
        int updatedRows = couponStockMapper.decreaseStockIfEnough(stockId);
        if (updatedRows != 1) {
            throw new NotEnoughCouponStockException();
        }

        Coupon coupon = buildCoupon(userId, stockId, couponName, couponType, price);
        int insertedRows = couponMapper.saveIfNotIssued(coupon);
        if (insertedRows != 1) {
            throw new AlreadyIssuedCouponException();
        }
        return coupon.getId();
    }

    /**
     * 묶음 발급 배치 전용. 재고 차감은 호출자(CreateCouponFacade)가 이미 대상 건수만큼
     * 끝냈다는 전제로, 여기서는 쿠폰 생성만 한 번의 다건 INSERT로 처리한다. 삽입된 행 수가
     * 요청 건수와 다르면(유니크 제약 위반 등) 배치 로직의 전제가 깨진 것이므로 예외로
     * 트랜잭션을 롤백시킨다 - 일부만 발급된 채로 조용히 넘어가지 않는다.
     */
    @Transactional
    public List<Coupon> issueCouponsBatch(int stockId, List<CouponIssueRequest> requests) {
        List<Coupon> coupons = requests.stream()
                .map(r -> buildCoupon(r.getUserId(), stockId, r.getCouponName(), r.getCouponType(), r.getPrice()))
                .toList();
        int insertedRows = couponMapper.saveAll(coupons);
        if (insertedRows != coupons.size()) {
            throw new IllegalStateException(
                    "Bulk coupon insert affected " + insertedRows + " rows, expected " + coupons.size());
        }
        return coupons;
    }

    @Transactional
    public void createCoupon(int userId, int stockId, String couponName, CouponType couponType, int price) {
        Coupon coupon = buildCoupon(userId, stockId, couponName, couponType, price);
        int insertedRows = couponMapper.saveIfNotIssued(coupon);
        if (insertedRows != 1) {
            throw new AlreadyIssuedCouponException();
        }
    }

    private Coupon buildCoupon(int userId, int stockId, String couponName, CouponType couponType, int price) {
        LocalDate now = LocalDate.now();
        Coupon coupon = Coupon.builder()
                .userId(userId)
                .stockId(stockId)
                .name(couponName)
                .couponType(couponType)
                .couponNumber(certificationGenerator.createCouponNumber(16))
                .price(price)
                .isUsed(false)
                .createdDate(now)
                .expiredDate(now.plusDays(30))
                .build();

        coupon.validatePrice(price);
        coupon.validateExpiredDate();
        return coupon;
    }

    public void useCoupon(Coupon coupon) {
        int updatedRows = couponMapper.updateUsedIfUnused(coupon.getId());
        if (updatedRows != 1) {
            throw new AlreadyRedeemedCouponException();
        }
    }

    public Coupon getCoupon(String couponNumber) {
        return couponMapper.getCouponByCouponNumber(couponNumber).orElseThrow(NotFoundCoupon::new);
    }

    /*public Coupon getCoupon(int couponId) {
        return couponMapper.getCouponById(couponId).orElseThrow(NotFoundCoupon::new);
    }*/
}
