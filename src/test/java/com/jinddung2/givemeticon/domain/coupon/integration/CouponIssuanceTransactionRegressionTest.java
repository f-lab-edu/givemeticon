package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.GivemeticonApplication;
import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

/**
 * Regression test for a fixed bug: with the real @DistributedLock -> AopForTransaction
 * (REQUIRES_NEW) wiring active, decreasing coupon_stock.remain and inserting the coupon row
 * used to happen as two independent @Transactional calls (CouponStockService.decreaseStock,
 * CouponService.createCoupon). Empirically, against the real Spring-proxied beans (not a
 * hand-wired mapper harness), a failure between those two calls left the stock decrement
 * committed with no coupon ever created - confirmed by first running this exact scenario
 * before the fix (remain went 3 -> 2 while 0 coupons were created).
 *
 * The fix combines both writes into one method, CouponService#issueCoupon, so they share a
 * single transaction by construction instead of relying on call-order convention. This test
 * now asserts the fixed behavior: a failure injected between the stock decrement and the
 * coupon insert rolls back the whole attempt, leaving remain unchanged.
 *
 * Requires the local infra stack (docker-compose.infra.yml up); boots the full application
 * context against localhost:3306/6379/6380/9092. The coupon/coupon_stock tables are created
 * here if missing (does not touch the app's real schema/migrations).
 */
@Tag("integration")
@SpringBootTest(classes = GivemeticonApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class CouponIssuanceTransactionRegressionTest {

    private static final String URL = "jdbc:mysql://localhost:3306/givemeticon";
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    @Autowired
    private CreateCouponFacade createCouponFacade;

    @SpyBean
    private CertificationGenerator certificationGenerator;

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
                    "expired_date DATE NOT NULL, UNIQUE KEY uk_coupon_user_stock (user_id, stock_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS coupon_issue_request (" +
                    "id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_id INT NOT NULL, user_id INT NOT NULL, " +
                    "status VARCHAR(20) NOT NULL, coupon_id INT NULL, reason VARCHAR(255) NULL, " +
                    "created_date DATETIME(6) NOT NULL, updated_date DATETIME(6) NOT NULL, " +
                    "UNIQUE KEY uk_coupon_issue_request_user_stock (user_id, stock_id))");
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
    void issueCoupon_failureAfterStockDecrease_rollsBackStockTooNoPartialState() throws Exception {
        int stockId = createStock(3);
        int userId = 123456;

        doThrow(new RuntimeException("simulated failure during coupon creation"))
                .when(certificationGenerator).createCouponNumber(anyInt());

        CreateCouponRequestDto requestDto = new CreateCouponRequestDto(stockId, "테스트 쿠폰", CouponType.FREE_POINT, 1000);

        assertThatThrownBy(() -> createCouponFacade.createCouponAndDecreaseStock(userId, requestDto))
                .isInstanceOf(RuntimeException.class);

        assertThat(couponCountFor(userId, stockId)).isEqualTo(0);
        assertThat(remainOf(stockId)).isEqualTo(3); // unchanged: decreaseStock rolled back with the failed insert
    }
}
