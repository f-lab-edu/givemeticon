package com.jinddung2.givemeticon.domain.point.integration;

import com.jinddung2.givemeticon.GivemeticonApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires the local infra stack (docker-compose.infra.yml) to be running:
 *   docker compose -f docker-compose.infra.yml up -d
 *
 * Real MySQL + full Spring context. cash_point (not Flyway-managed, predates it) is created
 * here manually if missing; cash_point_earn_history IS Flyway-managed
 * (db/migration/V20260919_3__add_cash_point_earn_history.sql) and is deliberately not created
 * here - if Flyway were misconfigured or the migration broken, every test below would fail
 * with "table 'cash_point_earn_history' doesn't exist" instead of exercising real logic.
 *
 * CashPointService is exercised directly (not through RedeemCouponFacade/UserService) since
 * it never touches the user/account tables - only cash_point and cash_point_earn_history.
 */
@Tag("integration")
@SpringBootTest(classes = GivemeticonApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class CashPointEarnHistoryIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3306/givemeticon";
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    @Autowired
    private CashPointService cashPointService;

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

    private int balanceOf(int cashPointId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT cash_point FROM cash_point WHERE id = " + cashPointId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int earnHistoryCountFor(int couponId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM cash_point_earn_history WHERE coupon_id = " + couponId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Coupon couponIssuedOn(int couponId, LocalDate createdDate) {
        Coupon coupon = Coupon.builder()
                .userId(1)
                .name("테스트 쿠폰")
                .couponNumber("COUPON" + couponId)
                .couponType(CouponType.FREE_POINT)
                .createdDate(createdDate)
                .build();
        setCouponId(coupon, couponId);
        return coupon;
    }

    private void setCouponId(Coupon coupon, int id) {
        try {
            Field field = Coupon.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(coupon, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("발급 후 7일 이내 사용이면 1만 포인트가 적립된다")
    void addPointForCouponRedeem_withinSevenDays_earnsPoints() throws Exception {
        int cashPointId = createCashPoint(0);
        User user = User.builder().cashPointId(cashPointId).build();
        LocalDate issuedDate = LocalDate.now().minusDays(3);
        Coupon coupon = couponIssuedOn(9001, issuedDate);

        cashPointService.addPointForCouponRedeem(user, coupon, LocalDate.now());

        assertThat(balanceOf(cashPointId)).isEqualTo(10_000);
        assertThat(earnHistoryCountFor(9001)).isEqualTo(1);
    }

    @Test
    @DisplayName("발급 후 7일이 지나 사용하면 적립되지 않는다")
    void addPointForCouponRedeem_afterSevenDays_doesNotEarnPoints() throws Exception {
        int cashPointId = createCashPoint(0);
        User user = User.builder().cashPointId(cashPointId).build();
        LocalDate issuedDate = LocalDate.now().minusDays(10);
        Coupon coupon = couponIssuedOn(9002, issuedDate);

        cashPointService.addPointForCouponRedeem(user, coupon, LocalDate.now());

        assertThat(balanceOf(cashPointId)).isEqualTo(0);
        assertThat(earnHistoryCountFor(9002)).isEqualTo(0);
    }

    @Test
    @DisplayName("[재시도 멱등성] 같은 쿠폰으로 다시 적립을 시도해도 잔액이 두 번 늘지 않는다")
    void addPointForCouponRedeem_retryForSameCoupon_doesNotEarnTwice() throws Exception {
        int cashPointId = createCashPoint(0);
        User user = User.builder().cashPointId(cashPointId).build();
        LocalDate issuedDate = LocalDate.now();
        Coupon coupon = couponIssuedOn(9003, issuedDate);

        cashPointService.addPointForCouponRedeem(user, coupon, LocalDate.now());
        cashPointService.addPointForCouponRedeem(user, coupon, LocalDate.now()); // 재시도

        assertThat(balanceOf(cashPointId)).isEqualTo(10_000);
        assertThat(earnHistoryCountFor(9003)).isEqualTo(1);
    }
}
