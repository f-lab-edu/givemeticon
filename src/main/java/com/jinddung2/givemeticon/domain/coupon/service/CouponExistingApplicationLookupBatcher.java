package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CouponAdmissionService#accept의 "기존 접수 조회"(findByEventIdAndMemberId)를 요청마다 개별
 * 커넥션 획득으로 실행하는 대신, 같은 행사에 짧은 시간(max-wait-millis) 안에 몰린 조회를 모아
 * 한 번의 findByEventIdAndMemberIds 다건 조회로 묶는다. 정합성 판단(어떤 회원에게 기존 접수가
 * 있는지/없는지)은 개별 조회와 완전히 같다 - 단지 그 조회를 실행하는 SQL 왕복·커넥션 획득 횟수를
 * 줄일 뿐이다.
 *
 * 근거(성능 개선 2단계, docs/coupon-portfolio): 부하 실행에서 Hikari 풀이 인스턴스당 20개 전부
 * active인 상태로 최대 181건이 커넥션을 기다렸다 - 종료 안내 대상(재고 소진 뒤 도착하는 대다수
 * 신규 회원)이 각자 독립적으로 findByEventIdAndMemberId를 호출해 짧은 시간에 수백~수천 건의
 * 개별 커넥션 획득이 몰린 것이 원인으로 보인다. 이미 이 코드베이스에서 신규 접수 insert 쪽에
 * 검증되어 있는 "짧게 모아 한 트랜잭션/한 쿼리로" 패턴(CouponBatchAdmissionAcceptor)을 읽기
 * 전용으로도 적용한다.
 *
 * 묶음 접수(CouponBatchAdmissionAcceptor)와 달리 이 클래스는 잠금·insert·행사 상태 판단을 전혀
 *하지 않는다 - 순수하게 "이 회원의 기존 접수 행이 있는가"만 다건으로 확인하는 읽기 전용 조회다.
 * 그래서 정합성 계약(캐시 확인 후 이 조회를 실행해야 하는 순서, 기존 접수 우선 반환)은
 * CouponAdmissionService#accept 쪽에서 그대로 유지된다 - 이 클래스는 "언제 조회하는지"에는
 * 관여하지 않고 "어떻게 묶어서 조회하는지"만 담당한다.
 */
@Component
@RequiredArgsConstructor
public class CouponExistingApplicationLookupBatcher {

    private static final int WORKER_THREADS = 4;

    @Value("${coupon.admission.existing-lookup-batch.size:50}")
    private int batchSize;

    @Value("${coupon.admission.existing-lookup-batch.max-wait-millis:15}")
    private long maxWaitMillis;

    private final CouponApplicationMapper couponApplicationMapper;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
    private final ConcurrentHashMap<Long, EventQueue> queuesByEvent = new ConcurrentHashMap<>();

    /**
     * 동기 호출부(accept())가 쓰는 진입점이다 - 내부적으로만 비동기(큐+드레인)로 처리한다.
     * 대기가 5초를 넘기거나 인터럽트되면 {@link UncertainException}을 던진다 - 조회 자체가
     * 실패로 확정된 게 아니라 "이 요청 스레드가 더 기다리지 않기로 했다"는 뜻이므로, 호출부가
     * 이를 CouponAdmissionOutcome.Checking으로 안내해야 한다(확정 실패로 위장하지 않는다).
     */
    public Optional<CouponApplication> lookup(long eventId, int memberId) {
        EventQueue eventQueue = queuesByEvent.computeIfAbsent(eventId, id -> new EventQueue());
        PendingLookup pending = new PendingLookup(memberId);
        if (!eventQueue.queue.offer(pending)) {
            throw new UncertainException("existing application lookup queue full", null);
        }
        scheduleDrainIfNeeded(eventId, eventQueue);
        try {
            return pending.future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncertainException("existing application lookup interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("existing application lookup failed", cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new UncertainException("existing application lookup timed out", e);
        }
    }

    /** accept()가 이 예외를 CouponAdmissionOutcome.Checking으로 변환한다 - 확정 실패가 아니다. */
    public static final class UncertainException extends RuntimeException {
        private UncertainException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void scheduleDrainIfNeeded(long eventId, EventQueue eventQueue) {
        if (eventQueue.draining.compareAndSet(false, true)) {
            executor.submit(() -> drain(eventId, eventQueue));
        }
    }

    private void drain(long eventId, EventQueue eventQueue) {
        try {
            List<PendingLookup> batch = new ArrayList<>(batchSize);
            while (true) {
                batch.clear();
                eventQueue.queue.drainTo(batch, batchSize);
                if (batch.isEmpty()) {
                    return;
                }
                if (batch.size() < batchSize) {
                    waitForMore(eventQueue.queue, batch);
                }
                flush(eventId, batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            eventQueue.draining.set(false);
            if (!eventQueue.queue.isEmpty() && eventQueue.draining.compareAndSet(false, true)) {
                executor.submit(() -> drain(eventId, eventQueue));
            }
        }
    }

    private void waitForMore(BlockingQueue<PendingLookup> queue, List<PendingLookup> batch)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(maxWaitMillis).toNanos();
        while (batch.size() < batchSize) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            PendingLookup next = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (next == null) {
                return;
            }
            batch.add(next);
            queue.drainTo(batch, batchSize - batch.size());
        }
    }

    private void flush(long eventId, List<PendingLookup> batch) {
        DistributionSummary.builder("coupon.admission.existing_lookup_batch.size")
                .description("한 조회 묶음에 포함된 요청 수(중복 회원 포함)")
                .register(meterRegistry)
                .record(batch.size());

        Set<Integer> distinctMemberIds = new LinkedHashSet<>();
        for (PendingLookup pending : batch) {
            distinctMemberIds.add(pending.memberId);
        }

        try {
            Map<Integer, CouponApplication> found = new HashMap<>();
            for (CouponApplication application : couponApplicationMapper.findByEventIdAndMemberIds(
                    eventId, new ArrayList<>(distinctMemberIds))) {
                found.put(application.getMemberId(), application);
            }
            for (PendingLookup pending : batch) {
                pending.future.complete(Optional.ofNullable(found.get(pending.memberId)));
            }
        } catch (RuntimeException e) {
            for (PendingLookup pending : batch) {
                pending.future.completeExceptionally(e);
            }
        }
    }

    private static final class EventQueue {
        // 신규 접수 큐(CouponBatchAdmissionAcceptor)와 달리 용량 상한이 필요 없다 - 이 조회는
        // insert가 없어 실패해도 "확정 성공을 잘못 안내"할 위험이 없고, 큐가 밀려도 최악의 경우
        // 개별 호출자가 5초 뒤 타임아웃으로 CHECKING을 받을 뿐이다(신규 접수 큐가 가득 찼을 때
        // 즉시 503으로 거절하는 것과는 위험의 성격이 다르다).
        private final BlockingQueue<PendingLookup> queue = new ArrayBlockingQueue<>(10_000);
        private final AtomicBoolean draining = new AtomicBoolean(false);
    }

    private static final class PendingLookup {
        private final int memberId;
        private final CompletableFuture<Optional<CouponApplication>> future = new CompletableFuture<>();

        private PendingLookup(int memberId) {
            this.memberId = memberId;
        }
    }
}
