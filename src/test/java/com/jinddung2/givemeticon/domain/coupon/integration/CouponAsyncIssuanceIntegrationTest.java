package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.GivemeticonApplication;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
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
 * "접수는 동기, 발급은 비동기" 경로(CreateCouponFacade#acceptOnly + #processNextPendingForStock)의
 * 정합성·순서·장애 복구 회귀 테스트. 실제 Spring 컨텍스트(@DistributedLock AOP 살아있음)를
 * 써야 processNextPendingForStock의 재고별 락이 실제로 동작하는지 검증할 수 있다 -
 * CouponIssueRequestLedgerIntegrationTest처럼 수동 조립한 SqlSessionFactory로는 AOP가
 * 걸리지 않아 락 자체를 검증하지 못한다(그 클래스의 주석 참고).
 *
 * Requires the local infra stack (docker-compose.infra.yml up).
 */
@Tag("integration")
@SpringBootTest(classes = GivemeticonApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class CouponAsyncIssuanceIntegrationTest {

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
            // coupon_issue_request는 CouponIssuanceTransactionRegressionTest와 같은 이유로
            // 여기서 만들지 않는다 - Flyway에만 맡긴다.
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
     * CouponIssueAsyncWorker#drain과 같은 계약: processNextPendingForStock이 재고
     * 소진(NotEnoughCouponStockException)/중복(AlreadyIssuedCouponException)으로 예외를
     * 던져도 "처리됨"으로 세고 계속 비운다 - 그래야 AopForTransaction의 REQUIRES_NEW
     * 트랜잭션이 예외로 깔끔히 롤백되지, 안에서 삼켜져 커밋 시도로 이어지지 않는다.
     */
    private int drainAll(int stockId) {
        int drained = 0;
        while (true) {
            try {
                if (!createCouponFacade.processNextPendingForStock(stockId)) {
                    return drained;
                }
                drained++;
            } catch (com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException
                     | com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException e) {
                drained++;
            }
        }
    }

    private int insertCoupon(int userId, int stockId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO coupon (user_id, stock_id, name, coupon_type, coupon_number, price, created_date, expired_date) " +
                            "VALUES (" + userId + ", " + stockId + ", '테스트 쿠폰', 'FREE_POINT', 'ABC', 1000, CURDATE(), CURDATE())",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("접수만 하면 재고는 그대로고 상태는 PENDING이다")
    void acceptOnly_doesNotTouchStock() throws Exception {
        int stockId = createStock(3);
        int userId = 10001;

        CouponIssueRequest request = createCouponFacade.acceptOnly(userId,
                new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000));

        assertThat(request.getStatus()).isEqualTo(CouponRequestStatus.PENDING);
        assertThat(remainOf(stockId)).isEqualTo(3);
    }

    @Test
    @DisplayName("접수 후 워커가 처리하면 재고가 줄고 상태가 ISSUED로 바뀐다")
    void processNextPendingForStock_issuesAcceptedRequest() throws Exception {
        int stockId = createStock(3);
        int userId = 10002;
        CouponIssueRequest request = createCouponFacade.acceptOnly(userId,
                new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000));

        boolean processed = createCouponFacade.processNextPendingForStock(stockId);

        assertThat(processed).isTrue();
        assertThat(statusOf(request.getId())).isEqualTo("ISSUED");
        assertThat(remainOf(stockId)).isEqualTo(2);
    }

    @Test
    @DisplayName("[장애 복구] 발급 트랜잭션은 커밋됐는데(쿠폰 존재) markIssued만 누락된 PENDING은 재고를 다시 건드리지 않고 기록만 채운다")
    void processNextPendingForStock_backfillsWhenCouponAlreadyExists() throws Exception {
        int stockId = createStock(3);
        int userId = 10003;
        CouponIssueRequest request = createCouponFacade.acceptOnly(userId,
                new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000));
        // issueCoupon()의 (재고 차감 + 쿠폰 생성)은 이미 커밋됐지만, markIssued 직전에
        // 죽어버린 상황을 재현한다 - 쿠폰은 있는데 접수는 아직 PENDING.
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE coupon_stock SET remain = remain - 1 WHERE id = " + stockId);
        }
        insertCoupon(userId, stockId);

        boolean processed = createCouponFacade.processNextPendingForStock(stockId);

        assertThat(processed).isTrue();
        assertThat(statusOf(request.getId())).isEqualTo("ISSUED");
        assertThat(remainOf(stockId)).isEqualTo(2); // 백필 경로는 재고를 다시 깎지 않는다(이미 2로 반영돼 있었음)
    }

    @Test
    @DisplayName("[중복·초과 발급] 재고보다 많은 서로 다른 회원이 접수하면, 정확히 재고 수만큼만 ISSUED고 나머지는 REJECTED다")
    void processNextPendingForStock_neverIssuesMoreThanStock() throws Exception {
        int total = 3;
        int stockId = createStock(total);
        int requestCount = 8;
        int baseUserId = 20000;

        List<CouponIssueRequest> accepted = IntStream.range(0, requestCount)
                .mapToObj(i -> createCouponFacade.acceptOnly(baseUserId + i,
                        new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000)))
                .collect(Collectors.toList());

        // 여러 "워커 스레드"가 동시에 같은 재고를 드레인한다 - CouponIssueAsyncWorker가
        // 앱 인스턴스 두 대에서 동시에 도는 상황을 흉내낸다. 스레드/건수는 로컬 Docker
        // MySQL에 짧은 REQUIRES_NEW 트랜잭션을 과도하게 몰아넣지 않을 만큼만 둔다 - 이
        // 값을 키운다고 검증 내용이 더 강해지지 않는다(순서·정합성은 이미 재고보다 요청이
        // 많기만 하면 드러난다).
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger drainedCount = new AtomicInteger();
        java.util.List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    drainedCount.addAndGet(drainAll(stockId));
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
        assertThat(drainedCount.get()).isEqualTo(requestCount);
        assertThat(remainOf(stockId)).isEqualTo(0);
        long issuedCount = 0;
        for (CouponIssueRequest request : accepted) {
            if ("ISSUED".equals(statusOf(request.getId()))) {
                issuedCount++;
            }
        }
        assertThat(issuedCount).isEqualTo(total);
    }

    @Test
    @DisplayName("[접수 순서] 재고보다 많은 요청이 동시에 드레인돼도, 먼저 접수된(id가 작은) 요청부터 ISSUED된다")
    void processNextPendingForStock_preservesAcceptanceOrderUnderConcurrentDraining() throws Exception {
        int total = 3;
        int stockId = createStock(total);
        int requestCount = 8;
        int baseUserId = 30000;

        // id 순서 = 접수 순서. 순서가 지켜진다면 ISSUED는 항상 "앞쪽 total개"여야 한다 -
        // 뒤쪽 요청이 이기고 앞쪽 요청이 REJECTED되는 일이 생기면 순서 위반이다.
        List<CouponIssueRequest> accepted = IntStream.range(0, requestCount)
                .mapToObj(i -> createCouponFacade.acceptOnly(baseUserId + i,
                        new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000)))
                .collect(Collectors.toList());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    drainAll(stockId);
                } catch (InterruptedException ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < accepted.size(); i++) {
            String expected = i < total ? "ISSUED" : "REJECTED";
            assertThat(statusOf(accepted.get(i).getId()))
                    .as("접수 %d번째(id=%d)는 %s여야 한다", i, accepted.get(i).getId(), expected)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("[멱등성] 같은 (회원, 재고)로 두 번 접수해도 같은 접수 건을 그대로 반환한다")
    void acceptOnly_retry_returnsSameRequest() throws Exception {
        int stockId = createStock(3);
        int userId = 40001;
        CreateCouponRequestDto requestDto = new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);

        CouponIssueRequest first = createCouponFacade.acceptOnly(userId, requestDto);
        CouponIssueRequest retry = createCouponFacade.acceptOnly(userId, requestDto);

        assertThat(retry.getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("[상태 조회] 접수 ID로 조회하면 처리 전후 상태가 그대로 보인다")
    void getOwnRequest_reflectsCurrentStatus() throws Exception {
        int stockId = createStock(3);
        int userId = 50001;
        CouponIssueRequest accepted = createCouponFacade.acceptOnly(userId,
                new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000));

        assertThat(createCouponFacade.getOwnRequest(userId, accepted.getId()).getStatus())
                .isEqualTo(CouponRequestStatus.PENDING);

        createCouponFacade.processNextPendingForStock(stockId);

        assertThat(createCouponFacade.getOwnRequest(userId, accepted.getId()).getStatus())
                .isEqualTo(CouponRequestStatus.ISSUED);
    }

    /**
     * 회귀 테스트: processNextPendingForStock 안에서 재고 소진 예외를 잡아 삼키면(정상
     * 반환처럼 보이면), AopForTransaction의 REQUIRES_NEW 트랜잭션이 이미 rollback-only로
     * 표시된 트랜잭션을 커밋 시도하다 UnexpectedRollbackException을 던지는 회귀가 있었다
     * (단일 스레드·순차 호출만으로도 재현됨 - 동시성과 무관한 버그였다). 이 테스트는 그
     * 상황(재고보다 많은 PENDING을 연속으로 드레인)을 그대로 재현해 고정한다.
     */
    @Test
    @DisplayName("[회귀] 재고 소진 후에도 남은 PENDING을 예외 없이 계속 드레인한다")
    void processNextPendingForStock_sequentialExhaustion_drainsWithoutUnexpectedRollback() throws Exception {
        int stockId = createStock(2);
        int userId = 99000;
        for (int i = 0; i < 5; i++) {
            createCouponFacade.acceptOnly(userId + i,
                    new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000));
        }

        int processed = drainAll(stockId);
        assertThat(processed).isEqualTo(5);
    }

}
