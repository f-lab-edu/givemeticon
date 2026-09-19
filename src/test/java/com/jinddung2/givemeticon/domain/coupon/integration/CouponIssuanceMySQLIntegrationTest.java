package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires the local infra stack (docker-compose.infra.yml) to be running:
 *   docker compose -f docker-compose.infra.yml up -d
 *
 * Real MySQL only (localhost:3306/givemeticon, matches application-local.yml). No Spring
 * context is started: CouponStockService/CouponService are instantiated directly against a
 * hand-built MyBatis SqlSessionFactory reading the project's real mapper XML
 * (src/main/resources/mapper/{CouponMapper,CouponStockMapper}.xml), wrapped in a
 * SqlSessionTemplate with ExecutorType.SIMPLE over an auto-commit DataSource. Each mapper
 * call therefore commits immediately and independently, exactly like a bare (non-Spring-proxied)
 * call would - which is what lets these tests observe real, immediately-visible cross-thread
 * and cross-step state instead of state hidden behind a test-managed rollback transaction
 * (which is why @MybatisTest, which wraps each test in a rolled-back transaction by default,
 * is deliberately NOT used here).
 *
 * Test-only schema: src/test/resources/coupon-test-schema.sql. Nothing here touches the
 * app's real schema or db/migration scripts.
 */
@Tag("integration")
class CouponIssuanceMySQLIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3306/givemeticon";
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    private static CouponMapper couponMapper;
    private static CouponStockMapper couponStockMapper;
    private static CouponService couponService;
    private static CouponStockService couponStockService;

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

        for (String resource : new String[]{"mapper/CouponMapper.xml", "mapper/CouponStockMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }

        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory, ExecutorType.SIMPLE);

        couponMapper = sqlSessionTemplate.getMapper(CouponMapper.class);
        couponStockMapper = sqlSessionTemplate.getMapper(CouponStockMapper.class);
        couponService = new CouponService(couponMapper, couponStockMapper, new CertificationGenerator());
        couponStockService = new CouponStockService(couponStockMapper, couponMapper, null);
    }

    private static void applySchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            InputStream in = CouponIssuanceMySQLIntegrationTest.class
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
    void cleanCouponRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM coupon");
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

    @Test
    @DisplayName("재고 변경: 동시 요청이 재고 수만큼만 성공하고, remain은 음수가 되지 않는다")
    void decreaseStock_concurrentRequests_neverOversells() throws Exception {
        int total = 5;
        int stockId = createStock(total);
        int threadCount = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger stockExhaustedCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    couponStockService.decreaseStock(stockId);
                    successCount.incrementAndGet();
                } catch (NotEnoughCouponStockException e) {
                    stockExhaustedCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(successCount.get()).isEqualTo(total);
        assertThat(stockExhaustedCount.get()).isEqualTo(threadCount - total);
        assertThat(remainOf(stockId)).isEqualTo(0);
    }

    @Test
    @DisplayName("쿠폰 발급: 동일 (userId, stockId) 동시 요청은 정확히 1건만 발급되고 나머지는 중복 예외를 받는다")
    void createCoupon_concurrentSameUserAndStock_onlyOneRowInserted() throws Exception {
        int stockId = createStock(10);
        int userId = 777;
        int threadCount = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    couponService.createCoupon(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);
                    successCount.incrementAndGet();
                } catch (AlreadyIssuedCouponException e) {
                    duplicateCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(threadCount - 1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("issueCoupon: 재고 차감과 쿠폰 생성이 한 번의 호출로 함께 일어난다")
    void issueCoupon_success_decreasesStockAndCreatesCouponTogether() throws Exception {
        int stockId = createStock(2);
        int userId = 999;

        couponService.issueCoupon(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);

        assertThat(remainOf(stockId)).isEqualTo(1);
        assertThat(couponCountFor(userId, stockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("issueCoupon: 재고가 없으면 예외를 던지고 쿠폰도 생성되지 않는다")
    void issueCoupon_stockExhausted_throwsAndCreatesNoCoupon() throws Exception {
        int stockId = createStock(0);
        int userId = 999;

        org.junit.jupiter.api.Assertions.assertThrows(NotEnoughCouponStockException.class,
                () -> couponService.issueCoupon(userId, stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000));

        assertThat(couponCountFor(userId, stockId)).isEqualTo(0);
    }

    // 실제 원자성(둘 중 하나가 실패하면 나머지도 롤백되는지)은 이 클래스의 수동 조립
    // SqlSessionFactory(JdbcTransactionFactory + autoCommit=true, Spring 트랜잭션 관리자
    // 없음)로는 검증할 수 없다 - 각 매퍼 호출이 그 자체로 즉시 커밋되기 때문에 "트랜잭션이
    // 실제로 걸려 있는지"를 이 harness는 애초에 관찰할 수 없다. 그 검증은 실제
    // @DistributedLock + @Transactional 프록시 체인이 살아있는 CouponIssuanceTransactionRegressionTest
    // (전체 Spring 컨텍스트 기반)에서 수행한다.
}
