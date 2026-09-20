package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponIssueRequestMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
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
 * Real MySQL only, wired the same way as CouponIssuanceMySQLIntegrationTest (hand-built
 * SqlSessionFactory, autoCommit=true, no Spring context). This class exercises
 * CouponIssueRequestService + CouponService together, replaying the exact call sequence
 * CreateCouponFacade performs (accept -> issueCoupon -> markIssued/markRejected) against a
 * real coupon_issue_request table, since CreateCouponFacade itself needs the
 * @DistributedLock AOP proxy (covered with mocks in CreateCouponFacadeTest instead).
 */
@Tag("integration")
class CouponIssueRequestLedgerIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3306/givemeticon";
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    private static CouponMapper couponMapper;
    private static CouponService couponService;
    private static CouponIssueRequestService couponIssueRequestService;
    private static CreateCouponFacade createCouponFacade;

    @BeforeAll
    static void setUpSchemaAndMappers() throws Exception {
        applySchema();

        DataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        ((UnpooledDataSource) dataSource).setAutoCommit(true);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias("Coupon", Coupon.class);
        configuration.getTypeAliasRegistry().registerAlias("CouponStock", CouponStock.class);
        configuration.getTypeAliasRegistry().registerAlias("CouponIssueRequest", CouponIssueRequest.class);

        for (String resource : new String[]{
                "mapper/CouponMapper.xml", "mapper/CouponStockMapper.xml", "mapper/CouponIssueRequestMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }

        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory, ExecutorType.SIMPLE);

        couponMapper = sqlSessionTemplate.getMapper(CouponMapper.class);
        CouponStockMapper couponStockMapper = sqlSessionTemplate.getMapper(CouponStockMapper.class);
        CouponIssueRequestMapper couponIssueRequestMapper = sqlSessionTemplate.getMapper(CouponIssueRequestMapper.class);

        couponService = new CouponService(couponMapper, couponStockMapper, new CertificationGenerator());
        CouponStockService couponStockService = new CouponStockService(couponStockMapper);
        couponIssueRequestService = new CouponIssueRequestService(couponIssueRequestMapper);
        // @DistributedLock is inert without a Spring AOP proxy - fine here, we're testing the
        // recovery LOGIC, not lock behavior (that's covered by CouponIssuanceTransactionRegressionTest's pattern).
        createCouponFacade = new CreateCouponFacade(
                couponService, couponStockService, couponIssueRequestService, couponMapper, new SimpleMeterRegistry());
    }

    private static void applySchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            InputStream in = CouponIssueRequestLedgerIntegrationTest.class
                    .getClassLoader().getResourceAsStream("coupon-test-schema.sql");
            String sql = Arrays.stream(new String(in.readAllBytes()).split("\n"))
                    .filter(line -> !line.strip().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    statement.execute(stmt);
                }
            }
        }
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM coupon");
            statement.execute("DELETE FROM coupon_issue_request");
            statement.execute("DELETE FROM coupon_stock");
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

    private int couponCountFor(int userId, int stockId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM coupon WHERE user_id = " + userId + " AND stock_id = " + stockId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** CreateCouponFacade가 하는 것과 같은 순서로 접수->발급을 수행한다. */
    private void issueViaLedger(int userId, int stockId, String couponName, int price) {
        CouponIssueRequestService.AcceptResult accepted =
                couponIssueRequestService.accept(userId, stockId, couponName, CouponType.FREE_POINT, price);
        if (!accepted.newlyAccepted()) {
            throw new IllegalStateException("already accepted, not retried in this helper");
        }
        int couponId = couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price);
        couponIssueRequestService.markIssued(accepted.request().getId(), couponId);
    }

    /** 재고 접수 기록의 updated_date를 과거로 되돌려, "오래 멈춘 PENDING" 상태를 재현한다. */
    private void backdateUpdatedDate(long requestId, int minutesAgo) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE coupon_issue_request SET updated_date = DATE_SUB(NOW(6), INTERVAL "
                    + minutesAgo + " MINUTE) WHERE id = " + requestId);
        }
    }

    @Test
    @DisplayName("[회귀] 크래시로 markIssued가 누락돼도, 재시도는 재고를 다시 깎지 않는다 (재시도 멱등성)")
    void retryAfterCrashBeforeMarkIssued_doesNotDecreaseStockAgain() throws Exception {
        int stockId = createStock(2);
        int userId = 999;

        // 1차 시도: 접수 + 발급(재고차감+쿠폰생성)까지는 성공, markIssued만 누락된 채 "크래시"
        CouponIssueRequestService.AcceptResult firstAttempt =
                couponIssueRequestService.accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        assertThat(firstAttempt.newlyAccepted()).isTrue();
        int couponId = couponService.issueCoupon(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        // markIssued(firstAttempt.request().getId(), couponId) 호출 없이 크래시했다고 가정

        assertThat(remainOf(stockId)).isEqualTo(1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);

        // 재시도: 같은 (userId, stockId)로 다시 접수를 시도한다.
        CouponIssueRequestService.AcceptResult retry =
                couponIssueRequestService.accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);

        // 새 행이 아니라 기존 PENDING 행을 그대로 돌려받는다 - 여기서 facade는 재발급을
        // 시도하지 않고 "확인 중" 예외를 던진다(CreateCouponFacadeTest에서 검증).
        assertThat(retry.newlyAccepted()).isFalse();
        assertThat(retry.request().getId()).isEqualTo(firstAttempt.request().getId());
        assertThat(retry.request().getStatus()).isEqualTo(CouponRequestStatus.PENDING);

        // 재시도가 couponService.issueCoupon을 다시 호출하지 않았으므로(newlyAccepted=false라
        // facade가 호출하지 않음) 재고는 1번만 줄어든 채로 유지된다 - 과거의 "재시도 시 재고
        // 2번 소모" 버그가 사라졌다.
        assertThat(remainOf(stockId)).isEqualTo(1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("접수 순서: 같은 재고에 대한 여러 사용자의 접수 id는 삽입 순서대로 증가한다")
    void acceptOrder_isMonotonicPerStock() throws Exception {
        int stockId = createStock(10);

        long id1 = couponIssueRequestService.accept(101, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000).request().getId();
        long id2 = couponIssueRequestService.accept(102, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000).request().getId();
        long id3 = couponIssueRequestService.accept(103, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000).request().getId();

        assertThat(id1).isLessThan(id2);
        assertThat(id2).isLessThan(id3);
    }

    @Test
    @DisplayName("동시에 같은 (userId, stockId)를 접수해도 정확히 한 건만 새로 접수된다")
    void accept_concurrentSameUserAndStock_onlyOneNewlyAccepted() throws Exception {
        int stockId = createStock(10);
        int userId = 555;
        int threadCount = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger newlyAcceptedCount = new AtomicInteger();
        List<Long> seenIds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    CouponIssueRequestService.AcceptResult result =
                            couponIssueRequestService.accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
                    if (result.newlyAccepted()) {
                        newlyAcceptedCount.incrementAndGet();
                    }
                    seenIds.add(result.request().getId());
                } catch (InterruptedException ignored) {
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(newlyAcceptedCount.get()).isEqualTo(1);
        assertThat(seenIds).hasSize(threadCount);
        assertThat(new java.util.HashSet<>(seenIds)).hasSize(1); // 모두 같은 접수 기록을 봤다
    }

    @Test
    @DisplayName("접수 후 발급까지 성공하면 재시도는 재발급 없이 같은 결과로 취급될 수 있다 (ISSUED 상태 확인)")
    void issueThenRetry_seesIssuedStatus() throws Exception {
        int stockId = createStock(2);
        int userId = 321;

        issueViaLedger(userId, stockId, "테스트 쿠폰", 1000);

        CouponIssueRequestService.AcceptResult retry =
                couponIssueRequestService.accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);

        assertThat(retry.newlyAccepted()).isFalse();
        assertThat(retry.request().getStatus()).isEqualTo(CouponRequestStatus.ISSUED);
        assertThat(retry.request().getCouponId()).isNotNull();
        // 재고는 정확히 한 번만 줄어든 채로 고정된다 - 재시도가 여기서 멈추면 더 깎이지 않는다.
        assertThat(remainOf(stockId)).isEqualTo(1);
    }

    // --- 장애 복구 (findStalePending + recoverPendingRequest) ---

    @Test
    @DisplayName("[장애 복구] updated_date가 기준보다 오래된 PENDING만 조회되고, 갓 접수된 PENDING은 조회되지 않는다")
    void findStalePending_onlyReturnsOldEnoughPendingRows() throws Exception {
        int stockId = createStock(10);

        CouponIssueRequestService.AcceptResult fresh =
                couponIssueRequestService.accept(1001, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        CouponIssueRequestService.AcceptResult stale =
                couponIssueRequestService.accept(1002, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        backdateUpdatedDate(stale.request().getId(), 5);

        List<com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest> staleRows =
                couponIssueRequestService.findStalePending(1);

        List<Long> staleIds = staleRows.stream().map(com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest::getId).toList();
        assertThat(staleIds).contains(stale.request().getId());
        assertThat(staleIds).doesNotContain(fresh.request().getId());
    }

    @Test
    @DisplayName("[장애 복구/회귀] 쿠폰은 이미 존재하는데 markIssued만 누락된 PENDING을 복구하면, 재고를 다시 건드리지 않고 ISSUED로 채워진다")
    void recoverPendingRequest_backfillsWithoutTouchingStockWhenCouponAlreadyExists() throws Exception {
        int stockId = createStock(2);
        int userId = 2001;

        CouponIssueRequestService.AcceptResult accepted =
                couponIssueRequestService.accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        int couponId = couponService.issueCoupon(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        // markIssued 호출 없이 크래시 - remain=1, coupon 1건, 원장은 여전히 PENDING

        createCouponFacade.recoverPendingRequest(accepted.request());

        var recovered = couponIssueRequestService.findById(accepted.request().getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(CouponRequestStatus.ISSUED);
        assertThat(recovered.getCouponId()).isEqualTo(couponId);
        // 재고는 크래시 시점 그대로(1) - 복구가 재고를 추가로 건드리지 않았다.
        assertThat(remainOf(stockId)).isEqualTo(1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("[장애 복구] 쿠폰이 아직 없는 PENDING(발급 트랜잭션이 실행된 적 없음)을 복구하면, 접수 시점 값으로 발급을 완료한다")
    void recoverPendingRequest_completesIssuanceWhenNoCouponExistsYet() throws Exception {
        int stockId = createStock(2);
        int userId = 2002;

        // 접수만 하고 발급 트랜잭션은 아예 호출되지 않은 채 "크래시"했다고 가정 (재고도 그대로)
        CouponIssueRequestService.AcceptResult accepted =
                couponIssueRequestService.accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        assertThat(remainOf(stockId)).isEqualTo(2);

        createCouponFacade.recoverPendingRequest(accepted.request());

        var recovered = couponIssueRequestService.findById(accepted.request().getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(CouponRequestStatus.ISSUED);
        assertThat(remainOf(stockId)).isEqualTo(1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("[장애 복구] 쿠폰이 없고 재고도 소진된 PENDING을 복구하면 REJECTED로 정리된다")
    void recoverPendingRequest_marksRejectedWhenStockExhausted() throws Exception {
        int stockId = createStock(1);
        int userId = 2003;
        int otherUserId = 2004;

        CouponIssueRequestService.AcceptResult accepted =
                couponIssueRequestService.accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        // 재고 1개뿐인 상황에서 다른 사용자가 먼저 발급받아 재고가 소진됐다고 가정
        couponService.issueCoupon(otherUserId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        assertThat(remainOf(stockId)).isEqualTo(0);

        assertThatThrownBy(() -> createCouponFacade.recoverPendingRequest(accepted.request()))
                .isInstanceOf(NotEnoughCouponStockException.class);

        var recovered = couponIssueRequestService.findById(accepted.request().getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(CouponRequestStatus.REJECTED);
    }

    @Test
    @DisplayName("[장애 복구] 그 사이 이미 처리된(더 이상 PENDING이 아닌) 접수는 다시 손대지 않는다")
    void recoverPendingRequest_alreadyResolved_doesNothing() throws Exception {
        int stockId = createStock(2);
        int userId = 2005;

        issueViaLedger(userId, stockId, "테스트 쿠폰", 1000);
        var issuedRequest = couponIssueRequestService
                .accept(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000).request();
        assertThat(issuedRequest.getStatus()).isEqualTo(CouponRequestStatus.ISSUED);

        createCouponFacade.recoverPendingRequest(issuedRequest);

        assertThat(remainOf(stockId)).isEqualTo(1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);
    }
}
