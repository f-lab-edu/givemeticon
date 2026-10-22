package com.jinddung2.givemeticon.domain.coupon.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-006 D4의 근거를 운영 스키마 없이 확인하는 잠금 가설 재현 테스트다.
 *
 * <ul>
 *   <li>현재 구조: 접수의 application INSERT는 FK 검사 때문에 부모(coupon_event에 해당하는 lk_event) 행에
 *       공유 잠금을 요청하므로, 발급이 그 행을 FOR UPDATE/UPDATE로 잡고 있으면 대기한다.</li>
 *   <li>바꿀 구조: FK 부모를 카운터(lk_counter)로 옮기면, 접수는 이미 배타 잠금으로 쥔 카운터 행에 FK 검사를
 *       하므로 발급의 lk_event 잠금과 경합하지 않는다.</li>
 * </ul>
 *
 * 운영 테이블(coupon_event, coupon_application 등)은 건드리지 않고 전용 스키마
 * {@value #SCHEMA}를 만들었다가 지운다. Requires the local givemeticon-mysql (localhost:3306).
 */
@Tag("integration")
class CouponEventLockHypothesisIntegrationTest {

    private static final String SCHEMA = "givemeticon_lock_hypothesis";
    private static final String SERVER_URL = "jdbc:mysql://localhost:3306/";
    private static final String URL = SERVER_URL + SCHEMA;
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    private static final int LOCK_WAIT_TIMEOUT_SECONDS = 2;
    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;

    private static boolean schemaReady;
    private static String skipReason = "schema was not created";

    @BeforeAll
    static void createSchema() {
        try (Connection admin = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            statement.execute("CREATE DATABASE " + SCHEMA);
        } catch (SQLException e) {
            skipReason = "cannot create schema " + SCHEMA + " (errorCode=" + e.getErrorCode()
                    + ", sqlState=" + e.getSQLState() + "): " + e.getMessage();
            System.out.println("[lock-hypothesis] SKIPPED " + skipReason);
            return;
        }
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE lk_event (" +
                    "id BIGINT PRIMARY KEY, issued_quantity INT NOT NULL, status VARCHAR(20) NOT NULL) ENGINE=InnoDB");
            statement.execute("CREATE TABLE lk_counter (" +
                    "event_id BIGINT PRIMARY KEY, next_seq BIGINT NOT NULL, " +
                    "FOREIGN KEY (event_id) REFERENCES lk_event(id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE lk_application_old (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT NOT NULL, member_id INT NOT NULL, seq BIGINT NOT NULL, " +
                    "FOREIGN KEY (event_id) REFERENCES lk_event(id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE lk_application_new (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT NOT NULL, member_id INT NOT NULL, seq BIGINT NOT NULL, " +
                    "FOREIGN KEY (event_id) REFERENCES lk_counter(event_id)) ENGINE=InnoDB");
            try (ResultSet rs = statement.executeQuery("SELECT VERSION()")) {
                rs.next();
                System.out.println("[lock-hypothesis] mysql_version=" + rs.getString(1));
            }
            schemaReady = true;
        } catch (SQLException e) {
            skipReason = "cannot create tables in " + SCHEMA + " (errorCode=" + e.getErrorCode() + "): " + e.getMessage();
            System.out.println("[lock-hypothesis] SKIPPED " + skipReason);
        }
    }

    @AfterAll
    static void dropSchema() {
        try (Connection admin = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        } catch (SQLException e) {
            System.out.println("[lock-hypothesis] WARNING could not drop " + SCHEMA + ": " + e.getMessage());
        }
    }

    @BeforeEach
    void resetRows() throws SQLException {
        assumeTrue(schemaReady, skipReason);
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM lk_application_old");
            statement.execute("DELETE FROM lk_application_new");
            statement.execute("DELETE FROM lk_counter");
            statement.execute("DELETE FROM lk_event");
            statement.execute("INSERT INTO lk_event (id, issued_quantity, status) VALUES (1, 0, 'OPEN')");
            statement.execute("INSERT INTO lk_counter (event_id, next_seq) VALUES (1, 0)");
        }
    }

    @Test
    @DisplayName("scenario 1: 현재 FK(부모=event)는 발급의 FOR UPDATE 동안 접수 INSERT를 막는다")
    void scenario_current_fk_blocks_admission_insert() throws Exception {
        Connection issuance = open(false);
        Connection admission = open(true);
        try {
            issuance.createStatement().execute("SELECT * FROM lk_event WHERE id=1 FOR UPDATE");

            long elapsed = expectLockTimeout(admission,
                    "INSERT INTO lk_application_old(event_id, member_id, seq) VALUES (1, 1, 1)");

            log("scenario_current_fk_blocks_admission_insert", "B fails with 1205 after ~2000ms",
                    "1205 after " + elapsed + "ms", elapsed);
            assertThat(elapsed).isGreaterThanOrEqualTo(1500L);
        } finally {
            closeAll(issuance, admission);
        }
    }

    @Test
    @DisplayName("scenario 2: 현재 FK(부모=event)는 발급의 UPDATE(커밋 전) 동안에도 접수 INSERT를 막는다")
    void scenario_current_fk_blocks_under_issuance_update() throws Exception {
        Connection issuance = open(false);
        Connection admission = open(true);
        try {
            issuance.createStatement().executeUpdate("UPDATE lk_event SET issued_quantity = issued_quantity + 1 WHERE id=1");

            long elapsed = expectLockTimeout(admission,
                    "INSERT INTO lk_application_old(event_id, member_id, seq) VALUES (1, 1, 1)");

            log("scenario_current_fk_blocks_under_issuance_update", "B fails with 1205 after ~2000ms",
                    "1205 after " + elapsed + "ms", elapsed);
            assertThat(elapsed).isGreaterThanOrEqualTo(1500L);
        } finally {
            closeAll(issuance, admission);
        }
    }

    @Test
    @DisplayName("scenario 3: FK 부모를 카운터로 옮기면 발급의 FOR UPDATE와 접수가 경합하지 않는다")
    void scenario_split_counter_does_not_block() throws Exception {
        Connection issuance = open(false);
        Connection admission = open(true);
        try {
            issuance.createStatement().execute("SELECT * FROM lk_event WHERE id=1 FOR UPDATE");

            long elapsed = runSplitAdmission(admission);

            log("scenario_split_counter_does_not_block", "B commits, elapsed < 1000ms",
                    "committed after " + elapsed + "ms", elapsed);
            assertThat(elapsed).isLessThan(1000L);
            assertSplitAdmissionPersisted();
        } finally {
            closeAll(issuance, admission);
        }
    }

    @Test
    @DisplayName("scenario 4: FK 부모를 카운터로 옮기면 발급의 UPDATE(커밋 전)와도 경합하지 않는다")
    void scenario_split_counter_does_not_block_under_issuance_update() throws Exception {
        Connection issuance = open(false);
        Connection admission = open(true);
        try {
            issuance.createStatement().executeUpdate("UPDATE lk_event SET issued_quantity = issued_quantity + 1 WHERE id=1");

            long elapsed = runSplitAdmission(admission);

            log("scenario_split_counter_does_not_block_under_issuance_update", "B commits, elapsed < 1000ms",
                    "committed after " + elapsed + "ms", elapsed);
            assertThat(elapsed).isLessThan(1000L);
            assertSplitAdmissionPersisted();
        } finally {
            closeAll(issuance, admission);
        }
    }

    @Test
    @DisplayName("scenario 5: 접수끼리는 카운터 행에서 여전히 직렬화된다")
    void scenario_counter_serializes_admissions() throws Exception {
        Connection first = open(true);
        Connection second = open(true);
        try {
            first.createStatement().execute("SELECT * FROM lk_counter WHERE event_id=1 FOR UPDATE");

            long elapsed = expectLockTimeout(second, "SELECT * FROM lk_counter WHERE event_id=1 FOR UPDATE");

            log("scenario_counter_serializes_admissions", "B2 fails with 1205 after ~2000ms",
                    "1205 after " + elapsed + "ms", elapsed);
            assertThat(elapsed).isGreaterThanOrEqualTo(1500L);
        } finally {
            closeAll(first, second);
        }
    }

    /** 접수 역할: 카운터 FOR UPDATE -> 순번 증가 -> application INSERT(FK 부모=카운터) -> COMMIT. 경과 시간(ms)을 돌려준다. */
    private long runSplitAdmission(Connection admission) throws SQLException {
        long start = System.nanoTime();
        try (Statement statement = admission.createStatement()) {
            statement.execute("SELECT * FROM lk_counter WHERE event_id=1 FOR UPDATE");
            statement.executeUpdate("UPDATE lk_counter SET next_seq = next_seq + 1 WHERE event_id=1");
            statement.executeUpdate("INSERT INTO lk_application_new(event_id, member_id, seq) VALUES (1, 1, 1)");
            admission.commit();
        } catch (SQLException e) {
            fail("split admission must not wait or fail, but got errorCode=" + e.getErrorCode() + ": " + e.getMessage());
        }
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private void assertSplitAdmissionPersisted() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT (SELECT COUNT(*) FROM lk_application_new WHERE event_id=1), " +
                             "(SELECT next_seq FROM lk_counter WHERE event_id=1)")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getLong(2)).isEqualTo(1L);
        }
    }

    /** sql을 실행해 innodb_lock_wait_timeout(1205)으로 실패하는지 확인하고, 실패까지 걸린 시간(ms)을 돌려준다. */
    private long expectLockTimeout(Connection connection, String sql) {
        long start = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            assertThat(e.getErrorCode()).as("expected lock wait timeout, got: " + e.getMessage())
                    .isEqualTo(ER_LOCK_WAIT_TIMEOUT);
            return elapsed;
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        log("unexpected", "1205", "statement succeeded after " + elapsed + "ms", elapsed);
        return fail("statement was expected to wait and time out with 1205, but it succeeded after " + elapsed + "ms: " + sql);
    }

    private Connection open(boolean admissionRole) throws SQLException {
        Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        if (admissionRole) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = " + LOCK_WAIT_TIMEOUT_SECONDS);
            }
        }
        return connection;
    }

    private static void closeAll(Connection... connections) {
        for (Connection connection : connections) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // the connection is closed right after; a failed rollback must not hide the test result
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // see above
            }
        }
    }

    private static void log(String scenario, String expected, String actual, long elapsedMs) {
        System.out.println("[lock-hypothesis] scenario=" + scenario + " expected=\"" + expected
                + "\" actual=\"" + actual + "\" elapsedMs=" + elapsedMs);
    }
}
