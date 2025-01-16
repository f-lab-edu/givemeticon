package com.jinddung2.givemeticon.common.config;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class CouponStockMetricConfig {

    private final MeterRegistry registry;
    private final CouponStockService couponStockService;

    @PostConstruct
    public void initCouponStockGauge() {
        List<CouponStock> activeCouponStockIds = couponStockService.getActiveCouponStockIds();

        for (CouponStock mapping : activeCouponStockIds) {
            int couponStockId = mapping.getId();

            Gauge.builder("coupon.stock.current",
                            couponStockService,
                            service -> service.getStock(couponStockId).getRemain())
                    .tag("couponId", String.valueOf(couponStockId))
                    .description("쿠폰별 남은 재고 모니터링")
                    .register(registry);
        }
    }

}
