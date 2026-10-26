package com.jinddung2.couponlab.coupon.service;

import java.net.ConnectException;
import java.net.SocketException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * S6: tells a database that cannot be reached (the Consumer pauses, nothing is counted as a failed attempt, nothing goes to
 * the DLQ) from everything else. Decided by the cause chain, so wrappers (Spring's, MyBatis', the listener's) do not matter.
 * An SQLException whose SQLState is in class 08 (connection exception, e.g. 08003 "connection closed" from a rollback after the
 * server went away) counts as well, and so does a failed commit or rollback (TransactionSystemException): it means the
 * connection broke at the end of the transaction (seen in the S6 poison-db-down run, where the rollback after a poison
 * message's own failure hit the cut). Pausing is safe either way: the message is redelivered and a commit that did happen
 * is found by its request id.
 * Deadlocks and lock-wait timeouts are not here: they are transient transaction errors and retried like any other failure.
 */
public final class S6ErrorClassifier {

    private S6ErrorClassifier() {
    }

    public static boolean isDatabaseDown(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof CannotGetJdbcConnectionException || cause instanceof CannotCreateTransactionException
                    || cause instanceof TransactionSystemException
                    || cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof SQLRecoverableException || cause instanceof ConnectException
                    || cause instanceof SocketException || isCommunicationsFailure(cause) || isConnectionSqlState(cause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionSqlState(Throwable cause) {
        return cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08");
    }

    private static boolean isCommunicationsFailure(Throwable cause) {
        return cause.getClass().getName().endsWith("CommunicationsException");
    }
}
