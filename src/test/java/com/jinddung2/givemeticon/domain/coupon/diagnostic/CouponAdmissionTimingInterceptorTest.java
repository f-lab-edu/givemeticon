package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponAdmissionTimingInterceptorTest {

    private static final String LOCK_SELECT_ID =
            "com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper.findByIdForUpdate";
    private static final String COUNTER_LOCK_SELECT_ID =
            "com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventAdmissionCounterMapper.findByEventIdForUpdate";
    private static final String READINESS_TX =
            "com.jinddung2.givemeticon.domain.coupon.mq.CouponMqReadinessTransactionService.initialize";
    private static final String ADMISSION_TX =
            "com.jinddung2.givemeticon.domain.coupon.service.CouponBatchAdmissionTransactionService.acceptBatch";
    private static final String ISSUANCE_TX =
            "com.jinddung2.givemeticon.domain.coupon.service.CouponEventIssuanceBatchTransactionService.processNext";
    private static final String SINGLE_ISSUANCE_TX =
            "com.jinddung2.givemeticon.domain.coupon.service.CouponEventIssuanceTransactionService.processNext";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CouponAdmissionTimingContext timingContext = new CouponAdmissionTimingContext();
    private final CouponAdmissionTimingInterceptor interceptor =
            new CouponAdmissionTimingInterceptor(registry, timingContext);

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.setCurrentTransactionName(null);
        timingContext.clear();
    }

    @Test
    @DisplayName("접수 트랜잭션의 잠금 SELECT는 caller=admission으로 기록되고 post_lock_work 기준 시각을 남긴다")
    void admissionCaller() throws Throwable {
        TransactionSynchronizationManager.setCurrentTransactionName(ADMISSION_TX);

        interceptor.intercept(invocation(COUNTER_LOCK_SELECT_ID, false));

        assertThat(count("admission", "success")).isEqualTo(1);
        assertThat(count("issuance", "success")).isZero();
        assertThat(timingContext.lockQueryCompletedNanosOrZero()).isNotZero();
    }

    @Test
    @DisplayName("접수 외 트랜잭션(MQ 준비 상태 등)의 coupon_event 잠금은 caller=other이며 접수 지표와 기준 시각을 오염시키지 않는다")
    void otherCallerForEventLockOutsideIssuance() throws Throwable {
        TransactionSynchronizationManager.setCurrentTransactionName(READINESS_TX);

        interceptor.intercept(invocation(LOCK_SELECT_ID, false));

        assertThat(count("other", "success")).isEqualTo(1);
        assertThat(count("admission", "success")).isZero();
        assertThat(count("issuance", "success")).isZero();
        assertThat(timingContext.lockQueryCompletedNanosOrZero()).isZero();
    }

    @Test
    @DisplayName("발급 워커(묶음·단건)의 잠금 SELECT는 caller=issuance로 분리되고 접수 기준 시각을 오염시키지 않는다")
    void issuanceCaller() throws Throwable {
        TransactionSynchronizationManager.setCurrentTransactionName(ISSUANCE_TX);
        interceptor.intercept(invocation(LOCK_SELECT_ID, false));
        TransactionSynchronizationManager.setCurrentTransactionName(SINGLE_ISSUANCE_TX);
        interceptor.intercept(invocation(LOCK_SELECT_ID, false));

        assertThat(count("issuance", "success")).isEqualTo(2);
        assertThat(count("admission", "success")).isZero();
        assertThat(timingContext.lockQueryCompletedNanosOrZero()).isZero();
    }

    @Test
    @DisplayName("SQL 실패는 outcome=error와 caller 태그로 기록되고 예외는 그대로 전파된다")
    void errorKeepsCallerTag() {
        TransactionSynchronizationManager.setCurrentTransactionName(ISSUANCE_TX);

        assertThatThrownBy(() -> interceptor.intercept(invocation(LOCK_SELECT_ID, true)))
                .hasRootCauseInstanceOf(SQLException.class); // 실제 MyBatis Plugin이 InvocationTargetException을 풀어준다

        assertThat(count("issuance", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 statement는 계측하지 않는다")
    void otherStatementIgnored() throws Throwable {
        interceptor.intercept(invocation("com.example.Other.select", false));

        assertThat(registry.find("coupon.admission.select_for_update").timers()).isEmpty();
    }

    private long count(String caller, String outcome) {
        var timer = registry.find("coupon.admission.select_for_update")
                .tag("caller", caller).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    /** 실제 RoutingStatementHandler(delegate.mappedStatement 경로 포함)에 mock PreparedStatement를 물린다. */
    private Invocation invocation(String statementId, boolean failExecute) throws Exception {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, "SELECT 1", Collections.emptyList(), null);
        SqlSource sqlSource = parameterObject -> boundSql;
        MappedStatement mappedStatement = new MappedStatement.Builder(configuration, statementId, sqlSource, SqlCommandType.SELECT).build();
        StatementHandler handler = new RoutingStatementHandler(
                mock(Executor.class), mappedStatement, null, RowBounds.DEFAULT, null, boundSql);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.getUpdateCount()).thenReturn(-1); // 결과 없음으로 MyBatis의 결과 순회를 끝낸다
        if (failExecute) {
            when(statement.execute()).thenThrow(new SQLException("boom"));
        }
        Method query = StatementHandler.class.getMethod("query", java.sql.Statement.class, ResultHandler.class);
        return new Invocation(handler, query, new Object[]{statement, null});
    }
}
