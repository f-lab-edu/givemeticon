package com.jinddung2.couponlab.coupon.service;

import java.net.ConnectException;
import java.net.SocketException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * S6: tells a database that cannot be reached (the Consumer pauses, nothing is counted as a failed attempt, nothing goes to
 * the DLQ) from everything else. Decided by the cause chain, so wrappers (Spring's, MyBatis', the listener's) do not matter.
 * Deadlocks and lock-wait timeouts are not here: they are transient transaction errors and retried like any other failure.
 */
public final class S6ErrorClassifier {

    private S6ErrorClassifier() {
    }

    public static boolean isDatabaseDown(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof CannotGetJdbcConnectionException || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof SQLRecoverableException || cause instanceof ConnectException
                    || cause instanceof SocketException || isCommunicationsFailure(cause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommunicationsFailure(Throwable cause) {
        return cause.getClass().getName().endsWith("CommunicationsException");
    }
}
