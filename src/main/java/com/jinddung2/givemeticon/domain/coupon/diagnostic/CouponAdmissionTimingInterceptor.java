package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Statement;

/**
 * lock select의 JDBC statement 실행 구간만 계측한다. 지표 이름 coupon.admission.select_for_update와 caller 태그는
 * series2와 같다.
 * <ul>
 *   <li>caller=admission: 접수가 순번 직렬화 지점을 잠그는 쿼리(접수 카운터 행 FOR UPDATE, ADR-006). series2에서는
 *       이 자리가 coupon_event FOR UPDATE였다.</li>
 *   <li>caller=issuance: 발급 워커의 coupon_event FOR UPDATE. 호출자는 이 쿼리를 실행하는 스레드의 현재 트랜잭션 이름
 *       (@Transactional 메서드의 클래스.메서드)에 .CouponEventIssuance가 있는지로 판별한다.</li>
 *   <li>caller=other: 그 밖의 coupon_event FOR UPDATE(예: MQ 준비 상태의 주기적 잠금). 접수 지표를 오염시키지 않는다.</li>
 * </ul>
 * 묶음 접수의 트랜잭션은 요청 스레드가 아니라 플러시 스레드에서 열리고 쿼리도 그 스레드에서 실행되므로,
 * 스레드 간 컨텍스트 전달이 필요 없다. 이 값에는 InnoDB 락 대기 외에 DB 실행,
 * 네트워크, 결과 전송이 포함되므로 순수 락 대기 시간으로 사용하면 안 된다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon.admission.diagnostics.enabled", havingValue = "true")
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, org.apache.ibatis.session.ResultHandler.class})
})
public class CouponAdmissionTimingInterceptor implements Interceptor {

    private static final String EVENT_LOCK_SELECT_ID =
            "com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper.findByIdForUpdate";
    private static final String COUNTER_LOCK_SELECT_ID =
            "com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventAdmissionCounterMapper.findByEventIdForUpdate";

    private static final String CALLER_ADMISSION = "admission";
    private static final String CALLER_ISSUANCE = "issuance";
    private static final String CALLER_OTHER = "other";
    private static final String ISSUANCE_TRANSACTION_MARKER = ".CouponEventIssuance";

    private final MeterRegistry meterRegistry;
    private final CouponAdmissionTimingContext timingContext;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MappedStatement mappedStatement = (MappedStatement) SystemMetaObject.forObject(statementHandler)
                .getValue("delegate.mappedStatement");
        if (mappedStatement == null) {
            return invocation.proceed();
        }
        String statementId = mappedStatement.getId();
        boolean counterLock = COUNTER_LOCK_SELECT_ID.equals(statementId);
        if (!counterLock && !EVENT_LOCK_SELECT_ID.equals(statementId)) {
            return invocation.proceed();
        }

        String caller = counterLock ? CALLER_ADMISSION : eventLockCaller();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = invocation.proceed();
            // post_lock_work 기준 시각은 접수 전용이다. 발급 스레드에는 이를 지우는 쪽이 없어 남기면 안 된다.
            if (CALLER_ADMISSION.equals(caller)) {
                timingContext.markLockQueryCompleted();
            }
            sample.stop(timer("success", caller));
            return result;
        } catch (Throwable throwable) {
            sample.stop(timer("error", caller));
            throw throwable;
        }
    }

    private static String eventLockCaller() {
        String transactionName = TransactionSynchronizationManager.getCurrentTransactionName();
        return transactionName != null && transactionName.contains(ISSUANCE_TRANSACTION_MARKER)
                ? CALLER_ISSUANCE
                : CALLER_OTHER;
    }

    private Timer timer(String outcome, String caller) {
        return Timer.builder("coupon.admission.select_for_update")
                .description("연결 획득 뒤 SELECT FOR UPDATE statement 실행부터 결과 반환까지")
                .tag("outcome", outcome)
                .tag("caller", caller)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
