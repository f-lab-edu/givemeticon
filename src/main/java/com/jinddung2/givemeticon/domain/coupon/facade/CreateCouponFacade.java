package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponErrorCode;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponRequestNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponUserMismatchException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CreateCouponFacade {

    private final CouponService couponService;
    private final CouponIssueRequestService couponIssueRequestService;
    private final CouponMapper couponMapper;
    private final MeterRegistry meterRegistry;

    /**
     * 접수(coupon_issue_request 삽입) → 발급(재고 차감 + 쿠폰 생성, 하나의 트랜잭션) 순서로
     * 진행한다. 접수 단계의 DB 유니크 제약이 "합의된 접수 순서"와 재시도 멱등성을 함께
     * 보장한다 - 같은 (userId, stockId) 재시도는 새 접수를 만들지 않고 기존 기록을 그대로
     * 따른다. 분산 락은 재고별 쓰기 경합을 줄이기 위한 보조 수단일 뿐, 정합성의 근거는
     * DB 제약(유니크 키, 조건부 UPDATE)이다.
     */
    @DistributedLock(key = "#requestDto.stockId")
    public void createCouponAndDecreaseStock(int userId, CreateCouponRequestDto requestDto) {
        int stockId = requestDto.stockId();

        CouponIssueRequestService.AcceptResult accepted = couponIssueRequestService.accept(
                userId, stockId, requestDto.couponName(), requestDto.couponType(), requestDto.price());
        CouponIssueRequest request = accepted.request();

        if (request.getStatus() == CouponRequestStatus.ISSUED) {
            log.info("Coupon request userId={} stockId={} already issued - idempotent retry, returning as success",
                    userId, stockId);
            return;
        }

        if (request.getStatus() == CouponRequestStatus.REJECTED) {
            throw toRejectionException(request.getReason());
        }

        // status == PENDING
        if (!accepted.newlyAccepted()) {
            // 이전 접수가 처리 중이거나, 처리 도중 장애로 결과가 확정되지 못한 채 남아있다.
            // 결과가 불확실한 상태이므로 조용히 재시도하지 않고 확인 중임을 알린다. 이 상태는
            // CouponIssueRequestRecoveryScheduler가 일정 시간 뒤 자동으로 찾아 정리한다.
            log.warn("Coupon request userId={} stockId={} requestId={} is still PENDING from an earlier attempt - outcome uncertain",
                    userId, stockId, request.getId());
            throw new GiveMeTiConException(CouponErrorCode.COUPON_REQUEST_PENDING);
        }

        attemptIssuance(request.getId(), userId, stockId, requestDto.couponName(), requestDto.couponType(), requestDto.price(),
                request.getCreatedDate(), "immediate");
    }

    /**
     * 장애 복구 배치가 호출하는 진입점. PENDING인 채로 오래 멈춰있는 접수 하나를 재검토해
     * 실제 상태에 맞게 정리한다:
     *   1) 그 사이 이미 처리됐다면(다른 복구 실행과 경합 등) 아무 것도 하지 않는다.
     *   2) 발급까지는 성공했는데 markIssued만 누락된 경우(재고 차감+쿠폰 생성은 issueCoupon
     *      한 트랜잭션으로 묶여 있으므로, 쿠폰이 존재한다는 것은 그 트랜잭션이 커밋됐다는
     *      뜻이다) - 있는 사실을 그대로 기록만 한다(재고를 다시 건드리지 않는다).
     *   3) 쿠폰이 없다면 애초에 발급 트랜잭션이 실행/커밋된 적이 없다는 뜻이므로(재고도
     *      소모되지 않았으므로) 접수 시점에 저장해둔 값 그대로 발급을 새로 시도한다.
     * 재고별 분산 락으로 감싸 살아있는 클라이언트 요청과 경합하지 않게 한다.
     */
    @DistributedLock(key = "#request.stockId")
    public void recoverPendingRequest(CouponIssueRequest request) {
        resolvePending(request.getId(), "recovered", "recovered_backfill");
    }

    /**
     * 접수만 하고 발급은 시도하지 않는다 - "접수는 동기, 발급은 비동기" 경로의 접수 단계.
     * accept() 자체는 INSERT IGNORE + 유니크 키만으로 이미 순서·멱등성이 보장되므로(같은
     * (userId, stockId) 재시도는 새 행을 만들지 않고 기존 접수를 그대로 반환), 이 메서드는
     * 동기 경로(createCouponAndDecreaseStock)와 달리 재고별 락을 쥐지 않는다 - 재고 차감이
     * 이 시점에 일어나지 않기 때문이다. 응답은 항상 현재 상태(PENDING/ISSUED/REJECTED)를
     * 그대로 반환하고 예외를 던지지 않는다 - 재시도·응답 유실 시 호출자가 상태를 보고
     * 판단하게 한다.
     */
    public CouponIssueRequest acceptOnly(int userId, CreateCouponRequestDto requestDto) {
        CouponIssueRequestService.AcceptResult accepted = couponIssueRequestService.accept(
                userId, requestDto.stockId(), requestDto.couponName(), requestDto.couponType(), requestDto.price());
        return accepted.request();
    }

    /**
     * 비동기 발급 워커가 호출한다. 이 재고(stockId)에서 가장 오래된 PENDING 접수 1건을
     * 골라 발급을 시도한다. "다음 건을 고르는 것"과 "발급"을 같은 재고별 분산 락 안에서
     * 하므로, 두 앱 인스턴스의 워커가 동시에 돌아도 항상 그 순간의 최솟값 id를 고르게 되고
     * -서로 다른 두 건을 동시에 고르는 경합 자체가 생기지 않는다. 처리할 PENDING이 없으면
     * false를 반환해, 호출자(워커)가 이 재고의 큐를 다 비웠는지 알 수 있게 한다.
     *
     * 재고 소진(NotEnoughCouponStockException)·중복(AlreadyIssuedCouponException)은 여기서
     * 잡지 않고 그대로 던진다 - 이 메서드 전체가 AopForTransaction의 REQUIRES_NEW 트랜잭션
     * 하나로 감싸여 있어서, 안에서 예외를 삼켜 "정상 반환"처럼 보이게 하면 issueCoupon()이
     * 이미 rollback-only로 표시해둔 그 트랜잭션을 바깥 AOP가 커밋 시도하게 되고, Spring이
     * UnexpectedRollbackException을 던진다(실제로 이 버그를 이렇게 재현해서 찾았다) - 예외를
     * 그대로 내보내야 AOP가 커밋 대신 롤백하고, 원래 예외가 그대로 호출자에게 전달된다.
     * 호출자(CouponIssueAsyncWorker)가 이 예외를 "처리됨 + 계속 드레인"으로 받아들인다.
     */
    @DistributedLock(key = "#stockId")
    public boolean processNextPendingForStock(int stockId) {
        Optional<CouponIssueRequest> next = couponIssueRequestService.findOldestPendingByStockId(stockId);
        if (next.isEmpty()) {
            return false;
        }
        resolvePending(next.get().getId(), "async", "async_backfill");
        return true;
    }

    /** 접수 ID로 상태를 조회한다. 본인 접수가 아니면(다른 회원의 requestId) 존재 자체를 알려주지 않는다. */
    public CouponIssueRequest getOwnRequest(int userId, long requestId) {
        CouponIssueRequest request = couponIssueRequestService.findById(requestId)
                .orElseThrow(CouponRequestNotFoundException::new);
        if (request.getUserId() != userId) {
            throw new CouponUserMismatchException();
        }
        return request;
    }

    /**
     * PENDING인 접수 하나를 실제 상태에 맞게 정리한다:
     *   1) 그 사이 이미 처리됐다면(다른 실행과 경합 등) 아무 것도 하지 않는다.
     *   2) 발급까지는 성공했는데 markIssued만 누락된 경우(재고 차감+쿠폰 생성은 issueCoupon
     *      한 트랜잭션으로 묶여 있으므로, 쿠폰이 존재한다는 것은 그 트랜잭션이 커밋됐다는
     *      뜻이다) - 있는 사실을 그대로 기록만 한다(재고를 다시 건드리지 않는다).
     *   3) 쿠폰이 없다면 애초에 발급 트랜잭션이 실행/커밋된 적이 없다는 뜻이므로(재고도
     *      소모되지 않았으므로) 접수 시점에 저장해둔 값 그대로 발급을 새로 시도한다.
     * 호출자(recoverPendingRequest/processNextPendingForStock)가 이미 재고별 분산 락을
     * 쥔 상태에서 부른다는 전제다.
     */
    private void resolvePending(long requestId, String issuedPath, String backfillPath) {
        CouponIssueRequest current = couponIssueRequestService.findById(requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "coupon_issue_request " + requestId + " disappeared while resolving"));

        if (current.getStatus() != CouponRequestStatus.PENDING) {
            log.info("Coupon request id={} was already resolved to {} - skipping", current.getId(), current.getStatus());
            return;
        }

        Optional<Coupon> existingCoupon = couponMapper.findByUserIdAndStockId(current.getUserId(), current.getStockId());
        if (existingCoupon.isPresent()) {
            couponIssueRequestService.markIssued(current.getId(), existingCoupon.get().getId());
            log.info("Resolved coupon request id={} by backfilling existing coupon id={} (markIssued had failed to run)",
                    current.getId(), existingCoupon.get().getId());
            recordIssueDuration(current.getCreatedDate(), "issued", backfillPath);
            return;
        }

        attemptIssuance(current.getId(), current.getUserId(), current.getStockId(),
                current.getCouponName(), current.getCouponType(), current.getPrice(),
                current.getCreatedDate(), issuedPath);
    }

    private void attemptIssuance(long requestId, int userId, int stockId, String couponName, CouponType couponType, int price,
                                  LocalDateTime acceptedAt, String path) {
        try {
            int couponId = couponService.issueCoupon(userId, stockId, couponName, couponType, price);
            couponIssueRequestService.markIssued(requestId, couponId);
            recordIssueDuration(acceptedAt, "issued", path);
        } catch (NotEnoughCouponStockException e) {
            couponIssueRequestService.markRejected(requestId, CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name());
            recordIssueDuration(acceptedAt, "rejected", path);
            throw e;
        } catch (AlreadyIssuedCouponException e) {
            couponIssueRequestService.markRejected(requestId, CouponErrorCode.COUPON_ALREADY_ISSUED.name());
            recordIssueDuration(acceptedAt, "rejected", path);
            throw e;
        }
    }

    /**
     * 접수(request.createdDate)부터 이 요청의 결과가 최종 확정된 시점까지 걸린 시간을 기록한다.
     * 즉시 처리(immediate)는 사실상 HTTP 응답시간과 겹치지만, 장애로 PENDING에 머물다 복구
     * 배치가 뒤늦게 정리한 경우(recovered, recovered_backfill)는 이 지표로만 보인다 - 부하
     * 목표(정상 시 접수 p95 2초, 최종 결과 3분)를 이 타이머로 직접 관찰한다.
     */
    private void recordIssueDuration(LocalDateTime acceptedAt, String outcome, String path) {
        Timer.builder("coupon.issue.duration")
                .description("쿠폰 접수부터 최종 결과 확정까지 걸린 시간")
                .tag("outcome", outcome)
                .tag("path", path)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.between(acceptedAt, LocalDateTime.now()));
    }

    private RuntimeException toRejectionException(String reason) {
        if (CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name().equals(reason)) {
            return new NotEnoughCouponStockException();
        }
        if (CouponErrorCode.COUPON_ALREADY_ISSUED.name().equals(reason)) {
            return new AlreadyIssuedCouponException();
        }
        return new GiveMeTiConException(CouponErrorCode.NOT_ENOUGH_COUPON_STOCK);
    }
}
