package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface CouponIssueRequestMapper {
    int insertIgnore(CouponIssueRequest request);

    Optional<CouponIssueRequest> findByUserIdAndStockId(@Param("userId") int userId, @Param("stockId") int stockId);

    Optional<CouponIssueRequest> findById(@Param("id") long id);

    List<CouponIssueRequest> findStalePending(@Param("olderThanMinutes") long olderThanMinutes);

    Optional<CouponIssueRequest> findOldestPendingByStockId(@Param("stockId") int stockId);

    /** 묶음 발급 워커가 이 재고(stock_id)에서 다음으로 처리할 접수를 접수번호 오름차순으로 최대 limit건 고른다. */
    List<CouponIssueRequest> findPendingBatch(@Param("stockId") int stockId, @Param("limit") int limit);

    List<Integer> findDistinctPendingStockIds();

    int markIssued(@Param("id") long id, @Param("couponId") int couponId);

    int markRejected(@Param("id") long id, @Param("reason") String reason);

    /** items의 각 원소는 "requestId"/"couponId" 키를 가진 Map이다. 한 번의 UPDATE로 다건을 ISSUED 처리한다. */
    int markIssuedBatch(@Param("items") List<Map<String, Object>> items);

    /** ids에 해당하는 PENDING 접수를 한 번의 UPDATE로 SOLD_OUT 처리한다. */
    int markSoldOutBatch(@Param("ids") List<Long> ids, @Param("reason") String reason);
}
