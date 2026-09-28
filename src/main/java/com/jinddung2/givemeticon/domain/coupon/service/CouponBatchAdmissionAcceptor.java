package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponAdmissionQueueFullException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 신규 접수 후보를 행사별로 짧게 모아 CouponBatchAdmissionTransactionService에 한 트랜잭션으로 넘기는
 * 묶음 접수 경로다. coupon.admission.batch.enabled=true일 때만 등록되고, 그 외에는 기존
 * CouponAdmissionTransactionService가 그대로 쓰인다.
 *
 * 요청 스레드는 큐에 넣은 뒤 자신의 CompletableFuture가 완료될 때까지 대기한다 - 메모리에 쌓인
 * 시점이 아니라 DB 커밋(또는 확정된 실패)이 반영된 뒤에만 응답한다. 대기열은 이벤트마다 고정 용량이며,
 * 가득 차면 즉시 CouponAdmissionQueueFullException으로 거절한다(성공으로 안내하지 않는다).
 *
 * wait-timeout-millis 동안도 플러시가 끝나지 않거나(TimeoutException), 대기 자체가 인터럽트되면
 * (InterruptedException) CouponAdmissionOutcome.Checking을 반환한다 - 두 경우 모두 "이 요청 스레드가
 * 결과를 더 기다리지 않기로 했다"는 뜻일 뿐, pending.future나 드레인 스레드의 플러시 트랜잭션 자체가
 * 취소·롤백됐다는 뜻이 아니다. 실패 확정이 아니라 "나중에 다시 확인하라"는 뜻이며, 이 경우에도 커밋
 * 전 성공 응답은 만들지 않는다.
 *
 * CouponIssueAsyncWorker/CouponBatchIssueWorker와 같은 패턴(고정 스레드풀 + draining 플래그)으로
 * 이벤트마다 한 번에 하나의 드레인만 실행되게 한다.
 */
@Component
@ConditionalOnProperty(prefix = "coupon.admission.batch", name = "enabled", havingValue = "true")
@Slf4j
public class CouponBatchAdmissionAcceptor implements CouponAdmissionAcceptor {

    private static final int WORKER_THREADS = 8;

    @Value("${coupon.admission.batch.size:50}")
    private int batchSize;

    @Value("${coupon.admission.batch.max-wait-millis:15}")
    private long maxWaitMillis;

    @Value("${coupon.admission.batch.queue-capacity:2000}")
    private int queueCapacity;

    @Value("${coupon.admission.batch.wait-timeout-millis:5000}")
    private long waitTimeoutMillis;

    private final CouponBatchAdmissionTransactionService transactionService;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
    private final ConcurrentHashMap<Long, EventQueue> queuesByEvent = new ConcurrentHashMap<>();

    public CouponBatchAdmissionAcceptor(CouponBatchAdmissionTransactionService transactionService,
                                         MeterRegistry meterRegistry) {
        this.transactionService = transactionService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public CouponAdmissionOutcome acceptNewOrExisting(long eventId, int memberId) {
        EventQueue eventQueue = queuesByEvent.computeIfAbsent(eventId, id -> new EventQueue(queueCapacity));
        PendingAdmission pending = new PendingAdmission(memberId);
        if (!eventQueue.queue.offer(pending)) {
            meterRegistry.counter("coupon.admission.batch.queue_full").increment();
            throw new CouponAdmissionQueueFullException();
        }
        scheduleDrainIfNeeded(eventId, eventQueue);
        return await(eventId, pending);
    }

    private CouponAdmissionOutcome await(long eventId, PendingAdmission pending) {
        try {
            CouponApplication application = pending.future.get(waitTimeoutMillis, TimeUnit.MILLISECONDS);
            return new CouponAdmissionOutcome.Resolved(application);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 인터럽트는 "이 요청 스레드의 대기"만 중단시킨다. pending.future는 취소되지 않고, 드레인
            // 스레드의 플러시 트랜잭션도 이 인터럽트와 무관하게 계속 진행된다 - 이 스레드가 결과를
            // 더 기다리지 않기로 했을 뿐, 실제 커밋/롤백 여부를 알 수 없다는 점은 TimeoutException과
            // 같다. 따라서 확정된 실패로 던지지 않고 CHECKING으로 응답한다.
            log.warn("coupon batch admission wait interrupted; treating as CHECKING eventId={} memberId={}",
                    eventId, pending.memberId);
            return new CouponAdmissionOutcome.Checking(eventId);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("batch admission failed", cause);
        } catch (TimeoutException e) {
            // 이 요청이 속한 플러시가 아직 끝나지 않았을 뿐, 실패가 확정된 것은 아니다. pending.future는
            // 취소하지 않는다 - 드레인 스레드는 계속 진행 중이며, 나중에 커밋되면 future도 정상 완료된다.
            // 다만 이 스레드는 더 기다리지 않고 "결과 확인 중"으로 응답한다. 이후 조회는
            // CouponAdmissionService#getOwnApplicationForEvent로 실제 원장 상태를 다시 읽어야 한다.
            log.warn("coupon batch admission wait timed out; treating as CHECKING eventId={} memberId={}",
                    eventId, pending.memberId);
            return new CouponAdmissionOutcome.Checking(eventId);
        }
    }

    private void scheduleDrainIfNeeded(long eventId, EventQueue eventQueue) {
        if (eventQueue.draining.compareAndSet(false, true)) {
            executor.submit(() -> drain(eventId, eventQueue));
        }
    }

    private void drain(long eventId, EventQueue eventQueue) {
        try {
            List<PendingAdmission> batch = new ArrayList<>(batchSize);
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
            // 해제 직후 새 항목이 들어왔을 수 있으므로 다시 확인한다. 그 사이 다른 생산자가 이미
            // 드레인을 재개했다면 compareAndSet이 실패하므로 중복 드레인은 만들지 않는다.
            if (!eventQueue.queue.isEmpty() && eventQueue.draining.compareAndSet(false, true)) {
                executor.submit(() -> drain(eventId, eventQueue));
            }
        }
    }

    private void waitForMore(BlockingQueue<PendingAdmission> queue, List<PendingAdmission> batch)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(maxWaitMillis).toNanos();
        while (batch.size() < batchSize) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            PendingAdmission next = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (next == null) {
                return;
            }
            batch.add(next);
            queue.drainTo(batch, batchSize - batch.size());
        }
    }

    private void flush(long eventId, List<PendingAdmission> batch) {
        DistributionSummary.builder("coupon.admission.batch.size")
                .description("한 플러시 트랜잭션에 포함된 요청 수(중복 회원 포함)")
                .register(meterRegistry)
                .record(batch.size());
        Timer queueWaitTimer = Timer.builder("coupon.admission.batch.queue_wait")
                .description("요청이 큐에 들어온 시점부터 플러시가 시작되기까지의 대기")
                .publishPercentileHistogram()
                .register(meterRegistry);
        long flushStartNanos = System.nanoTime();
        for (PendingAdmission pending : batch) {
            queueWaitTimer.record(flushStartNanos - pending.enqueuedAtNanos, TimeUnit.NANOSECONDS);
        }

        Set<Integer> distinctMemberIds = new LinkedHashSet<>();
        for (PendingAdmission pending : batch) {
            distinctMemberIds.add(pending.memberId);
        }

        try {
            CouponBatchAdmissionResult result = transactionService.acceptBatch(eventId, new ArrayList<>(distinctMemberIds));
            for (PendingAdmission pending : batch) {
                CouponApplication resolved = result.resolved().get(pending.memberId);
                if (resolved != null) {
                    pending.future.complete(resolved);
                } else {
                    pending.future.completeExceptionally(result.failures().get(pending.memberId));
                }
            }
        } catch (RuntimeException e) {
            // 트랜잭션이 통째로 롤백됐다 - 신규 순번·insert가 없던 일이 됐으므로 묶음 전원에게
            // 같은 실패를 돌려준다. 재시도는 멱등하다(아무것도 커밋되지 않았기 때문).
            log.warn("coupon batch admission flush failed for eventId={}, batchSize={}", eventId, batch.size(), e);
            for (PendingAdmission pending : batch) {
                pending.future.completeExceptionally(e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    private static final class EventQueue {
        private final BlockingQueue<PendingAdmission> queue;
        private final AtomicBoolean draining = new AtomicBoolean(false);

        private EventQueue(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }
    }

    private static final class PendingAdmission {
        private final int memberId;
        private final long enqueuedAtNanos = System.nanoTime();
        private final CompletableFuture<CouponApplication> future = new CompletableFuture<>();

        private PendingAdmission(int memberId) {
            this.memberId = memberId;
        }
    }
}
