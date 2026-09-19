package com.jinddung2.givemeticon.domain.point.integration;

import com.jinddung2.givemeticon.GivemeticonApplication;
import com.jinddung2.givemeticon.domain.point.exception.NotEnoughCashPointException;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requires the local infra stack (docker-compose.infra.yml) to be running:
 *   docker compose -f docker-compose.infra.yml up -d
 *
 * Real MySQL + full Spring context, same pattern as CashPointEarnHistoryIntegrationTest.
 * CashPointService is autowired directly (not through SpendCashPointFacade/UserService) so
 * that its @Transactional is exercised through a real Spring proxy - which is what lets the
 * rollback test below actually prove something, unlike a hand-wired non-Spring harness.
 *
 * remaining_amount is Flyway-managed (V20260919_4__add_remaining_amount_to_cash_point_earn_history.sql).
 */
@Tag("integration")
@SpringBootTest(classes = GivemeticonApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class CashPointSpendIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3306/givemeticon";
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    @Autowired
    private CashPointService cashPointService;

    @Autowired
    private RedissonClient redissonClient;

    @BeforeAll
    static void ensureSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cash_point (" +
                    "id INT PRIMARY KEY AUTO_INCREMENT, cash_point INT NOT NULL, created_date DATETIME NULL)");
        }
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM cash_point_earn_history");
            statement.execute("DELETE FROM cash_point");
        }
    }

    private int createCashPoint(int balance) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO cash_point (cash_point, created_date) VALUES (" + balance + ", NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    /** 실제 적립 흐름을 거치지 않고, 쿠폰 없이 테스트가 원하는 값으로 적립 건을 직접 만든다. */
    private long createEarnBatch(int cashPointId, int couponId, int remainingAmount, LocalDate earnedDate, LocalDate expiredDate) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO cash_point_earn_history (cash_point_id, coupon_id, amount, remaining_amount, earned_date, expired_date) " +
                            "VALUES (" + cashPointId + ", " + couponId + ", " + remainingAmount + ", " + remainingAmount + ", '" + earnedDate + "', '" + expiredDate + "')",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private int balanceOf(int cashPointId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT cash_point FROM cash_point WHERE id = " + cashPointId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int remainingOf(long earnHistoryId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT remaining_amount FROM cash_point_earn_history WHERE id = " + earnHistoryId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("먼저 적립된(=먼저 만료되는) 건부터 순서대로 소진한다 (FIFO)")
    void spendPoint_consumesOldestBatchFirst() throws Exception {
        int cashPointId = createCashPoint(7_000);
        long older = createEarnBatch(cashPointId, 1, 2_000, LocalDate.now().minusDays(10), LocalDate.now().plusDays(20));
        long newer = createEarnBatch(cashPointId, 2, 5_000, LocalDate.now().minusDays(1), LocalDate.now().plusDays(29));
        User user = User.builder().cashPointId(cashPointId).build();

        cashPointService.spendPoint(user, 3_000, LocalDate.now());

        assertThat(remainingOf(older)).isEqualTo(0);   // 2,000 전부 소진
        assertThat(remainingOf(newer)).isEqualTo(4_000); // 나머지 1,000만 소진
        assertThat(balanceOf(cashPointId)).isEqualTo(4_000);
    }

    @Test
    @DisplayName("이미 만료된 적립 건은 잔액이 남아있어도 사용 대상에서 제외된다")
    void spendPoint_excludesExpiredBatch() throws Exception {
        int cashPointId = createCashPoint(5_000);
        long expired = createEarnBatch(cashPointId, 1, 5_000, LocalDate.now().minusDays(40), LocalDate.now().minusDays(10));
        User user = User.builder().cashPointId(cashPointId).build();

        assertThatThrownBy(() -> cashPointService.spendPoint(user, 1_000, LocalDate.now()))
                .isInstanceOf(NotEnoughCashPointException.class);

        assertThat(remainingOf(expired)).isEqualTo(5_000); // 손대지 않음
        assertThat(balanceOf(cashPointId)).isEqualTo(5_000); // 잔액(raw balance)도 그대로
    }

    @Test
    @DisplayName("[회귀] 유효 포인트가 부족하면, 일부 건에서 이미 차감을 시도했더라도 전부 롤백된다")
    void spendPoint_insufficientPoints_rollsBackPartialDeduction() throws Exception {
        int cashPointId = createCashPoint(2_000);
        long only = createEarnBatch(cashPointId, 1, 2_000, LocalDate.now(), LocalDate.now().plusMonths(1));
        User user = User.builder().cashPointId(cashPointId).build();

        // 2,000만 있는데 5,000을 쓰려고 하면, 알고리즘은 그 2,000짜리 건을 먼저 소진
        // 시도한 뒤(중간 상태) 총액이 모자란 것을 알고 예외를 던진다 - @Transactional이
        // 실제로 걸려있지 않다면 이 중간 소진이 그대로 남아버린다. 실제 Spring 프록시로
        // 이 클래스를 띄워 검증하는 이유가 바로 이것이다.
        assertThatThrownBy(() -> cashPointService.spendPoint(user, 5_000, LocalDate.now()))
                .isInstanceOf(NotEnoughCashPointException.class);

        assertThat(remainingOf(only)).isEqualTo(2_000); // 중간에 깎였던 것도 롤백되어 원상 복구
        assertThat(balanceOf(cashPointId)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("[동시성] 지갑 단위 분산 락으로 감싼 동시 사용 요청은 유효 포인트를 초과해 차감하지 않는다")
    void spendPoint_concurrentRequestsUnderLock_neverOverspend() throws Exception {
        int cashPointId = createCashPoint(10_000);
        createEarnBatch(cashPointId, 1, 10_000, LocalDate.now(), LocalDate.now().plusMonths(1));
        User user = User.builder().cashPointId(cashPointId).build();

        int threadCount = 50;
        int spendPerRequest = 1_000; // 10,000 / 1,000 = 10건만 성공해야 함
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger notEnoughCount = new AtomicInteger();
        List<Exception> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    // CreateCouponFacade/SpendCashPointFacade가 실제로 쓰는 것과 같은
                    // Redisson 락(DistributedLockAop.REDISSON_LOCK_PREFIX="LOCK:")으로
                    // 감싸, 락이 있을 때 동시 사용 요청이 실제로 안전한지를 검증한다.
                    RLock lock = redissonClient.getLock("LOCK:cashPointSpendTest:" + cashPointId);
                    boolean locked = lock.tryLock(5, 3, TimeUnit.SECONDS);
                    if (!locked) {
                        unexpected.add(new IllegalStateException("lock timeout"));
                        return;
                    }
                    try {
                        cashPointService.spendPoint(user, spendPerRequest, LocalDate.now());
                        successCount.incrementAndGet();
                    } catch (NotEnoughCashPointException e) {
                        notEnoughCount.incrementAndGet();
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } catch (Exception e) {
                    unexpected.add(e);
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).isEmpty();
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(notEnoughCount.get()).isEqualTo(threadCount - 10);
        assertThat(balanceOf(cashPointId)).isEqualTo(0); // 정확히 다 쓰고 음수로 내려가지 않음
    }
}
