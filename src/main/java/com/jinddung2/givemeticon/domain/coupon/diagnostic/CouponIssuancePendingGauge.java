package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 행사별 PENDING 잔량 게이지(coupon.issuance.pending{event_id})다. 스크래핑마다 DB를 치지 않도록
 * 1초 주기로 GROUP BY 한 번을 조회해 값을 캐시하고, 게이지는 캐시만 읽는다. 발급 워커가 켜진 앱에서만 뜬다.
 * 조회가 실패하면 직전 값을 유지하고 경고만 남긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "coupon.event-issuance.worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CouponIssuancePendingGauge {

    private final CouponApplicationMapper couponApplicationMapper;
    private final MeterRegistry meterRegistry;
    private final Map<Long, AtomicLong> pendingByEvent = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1_000L, initialDelay = 2_000L)
    public void refresh() {
        try {
            List<Map<String, Object>> rows = couponApplicationMapper.countPendingByEvent();
            pendingByEvent.values().forEach(value -> value.set(0));
            for (Map<String, Object> row : rows) {
                long eventId = ((Number) row.get("event_id")).longValue();
                long pending = ((Number) row.get("pending")).longValue();
                pendingByEvent.computeIfAbsent(eventId, this::register).set(pending);
            }
        } catch (RuntimeException e) {
            log.warn("coupon issuance pending gauge refresh failed - keeping previous values", e);
        }
    }

    private AtomicLong register(long eventId) {
        AtomicLong value = new AtomicLong();
        Gauge.builder("coupon.issuance.pending", value, AtomicLong::get)
                .description("행사별 발급 대기(PENDING) 신청 수, 1초 주기 표본")
                .tag("event_id", String.valueOf(eventId))
                .register(meterRegistry);
        return value;
    }
}
