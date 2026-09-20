package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.GivemeticonApplication;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "접수/발급 분리 + 묶음 차감" 경로(CreateCouponFacade#acceptOnly + #processBatchForStock)의
 * 정합성·순서·경계(재고 &lt; batchSize) 회귀 테스트. CouponAsyncIssuanceIntegrationTest와
 * 같은 이유로 실제 Spring 컨텍스트(@DistributedLock AOP 살아있음)를 쓴다.
 *
 * Requires the local infra stack (docker-compose.infra.yml up).
 */
@Tag("integration")
@SpringBootTest(classes = GivemeticonApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class CouponBatchIssuanceIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3306/givemeticon";
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    @Autowired
    private CreateCouponFacade createCouponFacade;

    @BeforeAll
    static void ensureSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS coupon_stock (" +
                    "id INT PRIMARY KEY AUTO_INCREMENT, total INT NOT NULL, remain INT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS coupon (" +
                    "id INT PRIMARY KEY AUTO_INCREMENT, user_id INT NOT NULL, stock_id INT NOT NULL, " +
                    "name VARCHAR(255) NOT NULL, coupon_type VARCHAR(50) NOT NULL, coupon_number VARCHAR(64) NOT NULL, " +
                    "price INT NOT NULL, is_used TINYINT(1) NOT NULL DEFAULT 0, created_date DATE NOT NULL, " +
                    "expired_date DATE NOT NULL)");
        }
    }

    private int createStock(int total) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO coupon_stock (total, remain) VALUES (" + total + ", " + total + ")",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int remainOf(int stockId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT remain FROM coupon_stock WHERE id = " + stockId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String statusOf(long requestId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT status FROM coupon_issue_request WHERE id = " + requestId)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * CouponBatchIssueWorker#drain과 같은 계약: batchSize보다 적게 처리되면 이 재고를 다
     * 비운 것이다. processBatchForStock의 락은 waitTime=0이므로(설계상 의도적으로 기다리지
     * 않는다), 이 헬퍼가 여러 스레드에서 같은 stockId로 동시에 불리면 LockAcquisitionFailedException을
     * 흔하게, 정상적으로 받는다 - 그 순간 다른 스레드가 리더였다는 뜻이므로 실패로 치지 않고
     * 다시 시도한다(운영 워커가 다음 폴링 틱에 다시 시도하는 것과 같은 효과).
     */
    private int drainAll(int stockId, int batchSize) {
        int drainedRequests = 0;
        while (true) {
            int processed;
            try {
                processed = createCouponFacade.processBatchForStock(stockId, batchSize);
            } catch (com.jinddung2.givemeticon.common.exception.LockAcquisitionFailedException e) {
                continue;
            }
            drainedRequests += processed;
            if (processed < batchSize) {
                return drainedRequests;
            }
        }
    }

    @Test
    @DisplayName("[전량 발급] 재고가 충분하면 배치 전체가 ISSUED되고 재고가 정확히 그만큼 준다")
    void processBatchForStock_EnoughStock_IssuesWholeBatch() throws Exception {
        int stockId = createStock(10);
        int baseUserId = 60000;
        List<CouponIssueRequest> accepted = IntStream.range(0, 4)
                .mapToObj(i -> createCouponFacade.acceptOnly(baseUserId + i,
                        new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000)))
                .collect(Collectors.toList());

        int processed = createCouponFacade.processBatchForStock(stockId, 10);

        assertThat(processed).isEqualTo(4);
        assertThat(remainOf(stockId)).isEqualTo(6);
        for (CouponIssueRequest request : accepted) {
            assertThat(statusOf(request.getId())).isEqualTo("ISSUED");
        }
    }

    @Test
    @DisplayName("[경계] 재고가 batchSize보다 적으면 접수번호 앞쪽부터 잔여만큼만 ISSUED, 나머지는 SOLD_OUT이다")
    void processBatchForStock_PartialStock_SplitsIssuedAndSoldOut() throws Exception {
        int stockId = createStock(2);
        int baseUserId = 61000;
        List<CouponIssueRequest> accepted = IntStream.range(0, 5)
                .mapToObj(i -> createCouponFacade.acceptOnly(baseUserId + i,
                        new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000)))
                .collect(Collectors.toList());

        int processed = createCouponFacade.processBatchForStock(stockId, 5);

        assertThat(processed).isEqualTo(5);
        assertThat(remainOf(stockId)).isEqualTo(0);
        for (int i = 0; i < accepted.size(); i++) {
            String expected = i < 2 ? "ISSUED" : "SOLD_OUT";
            assertThat(statusOf(accepted.get(i).getId()))
                    .as("접수 %d번째(id=%d)는 %s여야 한다", i, accepted.get(i).getId(), expected)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("[소진 이후 접수] 재고가 이미 0이면 다음 배치는 전부 SOLD_OUT으로 정리되고 재고는 더 깎이지 않는다")
    void processBatchForStock_AlreadyExhausted_AllSoldOutWithoutTouchingStock() throws Exception {
        int stockId = createStock(1);
        int baseUserId = 62000;
        createCouponFacade.acceptOnly(baseUserId, new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000));
        createCouponFacade.processBatchForStock(stockId, 10); // 재고를 0으로 소진시켜 둔다.
        assertThat(remainOf(stockId)).isEqualTo(0);

        List<CouponIssueRequest> lateComers = IntStream.range(0, 3)
                .mapToObj(i -> createCouponFacade.acceptOnly(baseUserId + 1 + i,
                        new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000)))
                .collect(Collectors.toList());

        int processed = createCouponFacade.processBatchForStock(stockId, 10);

        assertThat(processed).isEqualTo(3);
        assertThat(remainOf(stockId)).isEqualTo(0);
        for (CouponIssueRequest request : lateComers) {
            assertThat(statusOf(request.getId())).isEqualTo("SOLD_OUT");
        }
    }

    @Test
    @DisplayName("[선착순·동시 드레인] 재고보다 많은 서로 다른 회원이 접수하고 두 워커가 동시에 배치 드레인해도, 접수번호 기준 앞쪽 재고 수만큼만 ISSUED된다")
    void processBatchForStock_preservesAcceptanceOrderUnderConcurrentDraining() throws Exception {
        int total = 5;
        int stockId = createStock(total);
        int requestCount = 23;
        int baseUserId = 63000;
        int batchSize = 3;

        List<CouponIssueRequest> accepted = IntStream.range(0, requestCount)
                .mapToObj(i -> createCouponFacade.acceptOnly(baseUserId + i,
                        new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000)))
                .collect(Collectors.toList());

        // CouponBatchIssueWorker가 두 앱 인스턴스에서 동시에 도는 상황을 흉내낸다 -
        // waitTime=0 락이 그 순간의 "리더"만 실제로 처리하게 한다.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger drainedTotal = new AtomicInteger();
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    drainedTotal.addAndGet(drainAll(stockId, batchSize));
                } catch (InterruptedException ignored) {
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        assertThat(remainOf(stockId)).isEqualTo(0);
        for (int i = 0; i < accepted.size(); i++) {
            String expected = i < total ? "ISSUED" : "SOLD_OUT";
            assertThat(statusOf(accepted.get(i).getId()))
                    .as("접수 %d번째(id=%d)는 %s여야 한다", i, accepted.get(i).getId(), expected)
                    .isEqualTo(expected);
        }
    }
}
