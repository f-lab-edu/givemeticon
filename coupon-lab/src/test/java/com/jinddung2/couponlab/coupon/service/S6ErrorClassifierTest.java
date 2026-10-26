package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** S6: only connection failures pause the Consumer; transaction and data errors are attempts. */
class S6ErrorClassifierTest {

    @Test
    void connectionFailuresAreDatabaseDownHoweverDeeplyWrapped() {
        assertThat(S6ErrorClassifier.isDatabaseDown(new CannotCreateTransactionException("no tx"))).isTrue();
        assertThat(S6ErrorClassifier.isDatabaseDown(new CannotGetJdbcConnectionException("no conn"))).isTrue();
        assertThat(S6ErrorClassifier.isDatabaseDown(new ListenerExecutionFailedException("listener",
                new RuntimeException(new SQLTransientConnectionException("pool timeout"))))).isTrue();
        assertThat(S6ErrorClassifier.isDatabaseDown(new RuntimeException(new ConnectException("refused")))).isTrue();
    }

    @Test
    void transactionAndDataErrorsAreNotDatabaseDown() {
        assertThat(S6ErrorClassifier.isDatabaseDown(new CannotAcquireLockException("deadlock"))).isFalse();
        assertThat(S6ErrorClassifier.isDatabaseDown(new DuplicateKeyException("uq"))).isFalse();
        assertThat(S6ErrorClassifier.isDatabaseDown(new DataIntegrityViolationException("too long"))).isFalse();
        assertThat(S6ErrorClassifier.isDatabaseDown(new IllegalStateException("bug"))).isFalse();
    }
}
