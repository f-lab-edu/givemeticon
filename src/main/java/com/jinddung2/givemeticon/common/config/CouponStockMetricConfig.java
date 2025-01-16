package com.jinddung2.givemeticon.common.config;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class CouponStockMetricConfig {

    @Bean
    public MeterBinder stockSize(CouponStockService couponStockService) {
        return registry -> {
            // 활성 쿠폰 재고 목록 가져오기
            List<CouponStock> activeCouponStockIds = couponStockService.getActiveCouponStocks();

            for (CouponStock mapping : activeCouponStockIds) {
                int couponStockId = mapping.getId();

                // 각 쿠폰 ID에 대한 Gauge 등록
                Gauge.builder("my.coupon.stock.current",
                                couponStockService,
                                service -> service.getStock(couponStockId).getRemain())
                        .tag("couponId", String.valueOf(couponStockId))
                        .description("쿠폰별 남은 재고 모니터링")
                        .register(registry);
            }
        };
    }

}
