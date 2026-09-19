package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponIssueRequestMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
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

    private static CouponService couponService;
    private static CouponIssueRequestService couponIssueRequestService;

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

        CouponMapper couponMapper = sqlSessionTemplate.getMapper(CouponMapper.class);
        CouponStockMapper couponStockMapper = sqlSessionTemplate.getMapper(CouponStockMapper.class);
        CouponIssueRequestMapper couponIssueRequestMapper = sqlSessionTemplate.getMapper(CouponIssueRequestMapper.class);

        couponService = new CouponService(couponMapper, couponStockMapper, new CertificationGenerator());
        couponIssueRequestService = new CouponIssueRequestService(couponIssueRequestMapper);
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
        CouponIssueRequestService.AcceptResult accepted = couponIssueRequestService.accept(userId, stockId);
        if (!accepted.newlyAccepted()) {
            throw new IllegalStateException("already accepted, not retried in this helper");
        }
        int couponId = couponService.issueCoupon(userId, stockId, couponName, CouponType.FREE_POINT, price);
        couponIssueRequestService.markIssued(accepted.request().getId(), couponId);
    }

    @Test
    @DisplayName("[회귀] 크래시로 markIssued가 누락돼도, 재시도는 재고를 다시 깎지 않는다 (재시도 멱등성)")
    void retryAfterCrashBeforeMarkIssued_doesNotDecreaseStockAgain() throws Exception {
        int stockId = createStock(2);
        int userId = 999;

        // 1차 시도: 접수 + 발급(재고차감+쿠폰생성)까지는 성공, markIssued만 누락된 채 "크래시"
        CouponIssueRequestService.AcceptResult firstAttempt = couponIssueRequestService.accept(userId, stockId);
        assertThat(firstAttempt.newlyAccepted()).isTrue();
        int couponId = couponService.issueCoupon(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
        // markIssued(firstAttempt.request().getId(), couponId) 호출 없이 크래시했다고 가정

        assertThat(remainOf(stockId)).isEqualTo(1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);

        // 재시도: 같은 (userId, stockId)로 다시 접수를 시도한다.
        CouponIssueRequestService.AcceptResult retry = couponIssueRequestService.accept(userId, stockId);

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

        long id1 = couponIssueRequestService.accept(101, stockId).request().getId();
        long id2 = couponIssueRequestService.accept(102, stockId).request().getId();
        long id3 = couponIssueRequestService.accept(103, stockId).request().getId();

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
                    CouponIssueRequestService.AcceptResult result = couponIssueRequestService.accept(userId, stockId);
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

        CouponIssueRequestService.AcceptResult retry = couponIssueRequestService.accept(userId, stockId);

        assertThat(retry.newlyAccepted()).isFalse();
        assertThat(retry.request().getStatus()).isEqualTo(CouponRequestStatus.ISSUED);
        assertThat(retry.request().getCouponId()).isNotNull();
        // 재고는 정확히 한 번만 줄어든 채로 고정된다 - 재시도가 여기서 멈추면 더 깎이지 않는다.
        assertThat(remainOf(stockId)).isEqualTo(1);
    }
}
