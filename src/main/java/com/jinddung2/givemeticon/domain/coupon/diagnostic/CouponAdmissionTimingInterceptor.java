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

import java.sql.Statement;

/**
 * lock select의 JDBC statement 실행 구간만 계측한다. 이 값에는 InnoDB 락 대기 외에 DB 실행,
 * 네트워크, 결과 전송이 포함되므로 순수 락 대기 시간으로 사용하면 안 된다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon.admission.diagnostics.enabled", havingValue = "true")
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, org.apache.ibatis.session.ResultHandler.class})
})
public class CouponAdmissionTimingInterceptor implements Interceptor {

    private static final String LOCK_SELECT_ID =
            "com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper.findByIdForUpdate";

    private final MeterRegistry meterRegistry;
    private final CouponAdmissionTimingContext timingContext;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MappedStatement mappedStatement = (MappedStatement) SystemMetaObject.forObject(statementHandler)
                .getValue("delegate.mappedStatement");
        if (mappedStatement == null || !LOCK_SELECT_ID.equals(mappedStatement.getId())) {
            return invocation.proceed();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = invocation.proceed();
            timingContext.markLockQueryCompleted();
            sample.stop(timer("success"));
            return result;
        } catch (Throwable throwable) {
            sample.stop(timer("error"));
            throw throwable;
        }
    }

    private Timer timer(String outcome) {
        return Timer.builder("coupon.admission.select_for_update")
                .description("연결 획득 뒤 SELECT FOR UPDATE statement 실행부터 결과 반환까지")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
