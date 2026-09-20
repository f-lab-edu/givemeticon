package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CouponMapper {
    int save(Coupon coupon);

    int saveIfNotIssued(Coupon coupon);

    /**
     * 묶음 발급 전용 다건 INSERT. 각 coupon은 이미 유일한 접수(coupon_issue_request)
     * 한 건과 1:1로 대응하므로 IGNORE를 쓰지 않는다 - 여기서 유니크 제약 위반이 나면
     * 그 자체가 배치 로직의 전제(같은 재고에 같은 회원이 두 번 선택될 수 없음)가
     * 깨졌다는 뜻이므로, 조용히 건너뛰지 않고 트랜잭션을 실패시켜야 한다.
     */
    int saveAll(@Param("coupons") List<Coupon> coupons);

    int merge(Coupon coupon);

    int updateUsedIfUnused(@Param("id") int id);

    boolean existsByUserIdAndStockId(@Param("userId") int userId, @Param("stockId") int stockId);

    Optional<Coupon> findByUserIdAndStockId(@Param("userId") int userId, @Param("stockId") int stockId);

    Optional<Coupon> getCouponById(int couponId);

    Optional<Coupon> getCouponByCouponNumber(String couponNumber);
}
