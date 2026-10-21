package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotOpenException;
import com.jinddung2.givemeticon.domain.coupon.mq.CouponMqControl;
import com.jinddung2.givemeticon.domain.coupon.mq.CouponMqIntegrationMessages;
import com.jinddung2.givemeticon.domain.coupon.mq.CouponMqAdmissionTransactionService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAdmissionTransactionService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponBatchAdmissionTransactionService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponEventClosureCache;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssuanceProcessor;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * ADR-006(접수 카운터 분리)의 구현을 실제 MySQL에서 검증한다.
 *
 * 전용 스키마 {@value #SCHEMA}를 만들어 실제 마이그레이션 파일(coupon_event 관련 4~5개)을 Flyway로 버전 순서대로
 * 적용한다. 앱의 givemeticon 스키마와 다른 통합 테스트의 테이블은 건드리지 않는다. 서비스는 작은 Spring 컨텍스트
 * (실제 @Transactional 프록시 + 실제 Mapper XML)에 올리고, "앱 2대"는 DataSource가 따로인 컨텍스트 2개로 흉내 낸다.
 *
 * Requires the local givemeticon-mysql (localhost:3306).
 */
@Tag("integration")
class CouponEventCounterSplitIntegrationTest {

    private static final String SCHEMA = "givemeticon_counter_split_it";
    private static final String SERVER_URL = "jdbc:mysql://localhost:3306/";
    private static final String URL = SERVER_URL + SCHEMA;
    private static final String USER = "root";
    private static final String PASSWORD = "jin19970418";

    private static final Pattern COUPON_MIGRATIONS =
            Pattern.compile("V20260920__.*|V20260923__.*|V20261018__.*|V20261022(_2)?__.*");

    private static Path migrationDir;
    /** 발급이 단건 경로인 앱(앱 B 역할). */
    private static AnnotationConfigApplicationContext singleIssuanceApp;
    /** 발급이 묶음 경로인 앱(앱 A 역할). */
    private static AnnotationConfigApplicationContext batchIssuanceApp;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection admin = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            statement.execute("CREATE DATABASE " + SCHEMA);
        }
        migrationDir = Files.createTempDirectory("counter-split-migrations");
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.filter(f -> COUPON_MIGRATIONS.matcher(f.getFileName().toString()).matches()).toList()) {
                Files.copy(file, migrationDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Flyway.configure().dataSource(URL, USER, PASSWORD).locations("filesystem:" + migrationDir.toAbsolutePath())
                .load().migrate();
        singleIssuanceApp = newApp(false);
        batchIssuanceApp = newApp(true);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (singleIssuanceApp != null) {
            singleIssuanceApp.close();
        }
        if (batchIssuanceApp != null) {
            batchIssuanceApp.close();
        }
        try (Connection admin = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
        if (migrationDir != null) {
            try (Stream<Path> files = Files.list(migrationDir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(migrationDir);
        }
    }

    // ---------------------------------------------------------------- T1, T2

    @Test
    @DisplayName("T1: 접수(카운터 잠금 → 순번 증가 → application INSERT)는 발급이 coupon_event를 FOR UPDATE로 잡아도 기다리지 않는다")
    void admission_insert_does_not_wait_for_issuance_event_lock() throws Exception {
        long eventId = createEvent("OPEN", 10, 3);
        Connection issuance = open(false);
        Connection admission = open(true);
        try {
            issuance.createStatement().execute("SELECT * FROM coupon_event WHERE id=" + eventId + " FOR UPDATE");

            long start = System.nanoTime();
            try (Statement statement = admission.createStatement()) {
                statement.execute("SELECT next_acceptance_sequence FROM coupon_event_admission_counter WHERE event_id="
                        + eventId + " FOR UPDATE");
                statement.executeUpdate("UPDATE coupon_event_admission_counter SET next_acceptance_sequence = "
                        + "next_acceptance_sequence + 1 WHERE event_id=" + eventId);
                statement.executeUpdate("INSERT INTO coupon_application (public_request_id, event_id, member_id, "
                        + "acceptance_sequence, status) VALUES ('" + UUID.randomUUID() + "', " + eventId + ", 1, 1, 'PENDING')");
                admission.commit();
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000L;

            System.out.println("[counter-split] T1 elapsedMs=" + elapsed);
            assertThat(elapsed).isLessThan(1000L);
            assertThat(countApplications(eventId)).isEqualTo(1);
        } finally {
            closeAll(issuance, admission);
        }
    }

    @Test
    @DisplayName("T2: 발급(event FOR UPDATE → 신청 ISSUED UPDATE → award INSERT → issued_quantity 증가)은 접수가 카운터를 잡아도 기다리지 않는다")
    void issuance_status_update_does_not_wait_for_counter_lock() throws Exception {
        long eventId = createEvent("OPEN", 10, 3);
        insertPendingApplication(eventId, 1, 1);
        long applicationId = scalarLong("SELECT id FROM coupon_application WHERE event_id=" + eventId + " AND member_id=1");
        Connection admission = open(false);
        Connection issuance = open(true);
        try {
            admission.createStatement().execute("SELECT next_acceptance_sequence FROM coupon_event_admission_counter "
                    + "WHERE event_id=" + eventId + " FOR UPDATE");

            long start = System.nanoTime();
            try (Statement statement = issuance.createStatement()) {
                statement.execute("SELECT * FROM coupon_event WHERE id=" + eventId + " FOR UPDATE");
                assertThat(statement.executeUpdate("UPDATE coupon_application SET status='ISSUED', finalized_at=UTC_TIMESTAMP(6) "
                        + "WHERE id=" + applicationId + " AND status='PENDING'")).isEqualTo(1);
                statement.executeUpdate("INSERT INTO coupon_award (application_id, event_id, member_id, tier, points) VALUES ("
                        + applicationId + ", " + eventId + ", 1, 'HIGH', 1000)");
                statement.executeUpdate("UPDATE coupon_event SET issued_quantity = issued_quantity + 1 WHERE id=" + eventId);
                issuance.commit();
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000L;

            System.out.println("[counter-split] T2 elapsedMs=" + elapsed);
            assertThat(elapsed).isLessThan(1000L);
            assertThat(scalarLong("SELECT issued_quantity FROM coupon_event WHERE id=" + eventId)).isEqualTo(1L);
        } finally {
            closeAll(admission, issuance);
        }
    }

    // ---------------------------------------------------------------- T3

    @Test
    @DisplayName("T3(단건): total=30이면 100명이 8스레드로 접수해도 순번 1..30만 생기고 나머지 70명은 ENDED다")
    void v1_single_admission_closes_at_total_quantity() throws Exception {
        long eventId = createEvent("OPEN", 30, 10);
        CouponAdmissionTransactionService service = singleIssuanceApp.getBean(CouponAdmissionTransactionService.class);
        AtomicInteger ended = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int member = 1; member <= 100; member++) {
            int memberId = member;
            tasks.add(() -> {
                try {
                    service.acceptNewOrExisting(eventId, memberId);
                } catch (CouponEventNotOpenException e) {
                    ended.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        runConcurrently(8, tasks);

        assertThat(unexpected).isEmpty();
        assertThat(ended.get()).isEqualTo(70);
        assertSequencesExactly(eventId, 30);
        assertThat(counterValue(eventId)).isEqualTo(30L);
        assertThat(countByStatus(eventId, "SOLD_OUT")).isZero();
    }

    @Test
    @DisplayName("T3(묶음): total=30이면 100명이 8스레드로 접수해도 순번 1..30만 생기고 나머지 70명은 ENDED다")
    void v1_batch_admission_closes_at_total_quantity() throws Exception {
        long eventId = createEvent("OPEN", 30, 10);
        CouponBatchAdmissionTransactionService service = singleIssuanceApp.getBean(CouponBatchAdmissionTransactionService.class);
        AtomicInteger ended = new AtomicInteger();
        AtomicInteger resolved = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int batch = 0; batch < 20; batch++) {
            List<Integer> members = IntStream.rangeClosed(batch * 5 + 1, batch * 5 + 5).boxed().toList();
            tasks.add(() -> {
                try {
                    var result = service.acceptBatch(eventId, members);
                    resolved.addAndGet(result.resolved().size());
                    result.failures().values().forEach(failure -> {
                        if (failure instanceof CouponEventNotOpenException) {
                            ended.incrementAndGet();
                        } else {
                            unexpected.add(failure);
                        }
                    });
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        runConcurrently(8, tasks);

        assertThat(unexpected).isEmpty();
        assertThat(resolved.get()).isEqualTo(30);
        assertThat(ended.get()).isEqualTo(70);
        assertSequencesExactly(eventId, 30);
        assertThat(counterValue(eventId)).isEqualTo(30L);
        assertThat(countByStatus(eventId, "SOLD_OUT")).isZero();
    }

    // ---------------------------------------------------------------- T4

    @Test
    @DisplayName("T4(단건): 마지막 순번을 배정한 트랜잭션이 롤백되면 종료 캐시에 기록되지 않고, 커밋된 뒤에야 기록된다")
    void closure_cache_is_not_marked_by_uncommitted_last_sequence_single() throws Exception {
        CouponAdmissionTransactionService service = singleIssuanceApp.getBean(CouponAdmissionTransactionService.class);
        CouponEventClosureCache cache = singleIssuanceApp.getBean(CouponEventClosureCache.class);
        TransactionTemplate template = readCommittedTemplate(singleIssuanceApp);

        long rolledBack = createEvent("OPEN", 1, 1);
        try {
            template.executeWithoutResult(status -> {
                service.acceptNewOrExisting(rolledBack, 1);
                assertThat(cache.isKnownClosed(rolledBack)).as("not marked before commit").isFalse();
                throw new IllegalStateException("force rollback");
            });
            fail("expected the forced rollback");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("force rollback");
        }
        assertThat(cache.isKnownClosed(rolledBack)).as("rolled back: never marked").isFalse();
        assertThat(countApplications(rolledBack)).isZero();
        assertThat(counterValue(rolledBack)).isZero();

        long committed = createEvent("OPEN", 1, 1);
        template.executeWithoutResult(status -> {
            service.acceptNewOrExisting(committed, 1);
            assertThat(cache.isKnownClosed(committed)).as("not marked before commit").isFalse();
        });
        assertThat(cache.isKnownClosed(committed)).as("marked after commit").isTrue();
        assertThat(countApplications(committed)).isEqualTo(1);
    }

    @Test
    @DisplayName("T4(묶음): 마지막 순번을 배정한 트랜잭션이 롤백되면 종료 캐시에 기록되지 않고, 커밋된 뒤에야 기록된다")
    void closure_cache_is_not_marked_by_uncommitted_last_sequence_batch() throws Exception {
        CouponBatchAdmissionTransactionService service = singleIssuanceApp.getBean(CouponBatchAdmissionTransactionService.class);
        CouponEventClosureCache cache = singleIssuanceApp.getBean(CouponEventClosureCache.class);
        TransactionTemplate template = readCommittedTemplate(singleIssuanceApp);

        long rolledBack = createEvent("OPEN", 2, 1);
        try {
            template.executeWithoutResult(status -> {
                service.acceptBatch(rolledBack, List.of(1, 2));
                assertThat(cache.isKnownClosed(rolledBack)).as("not marked before commit").isFalse();
                throw new IllegalStateException("force rollback");
            });
            fail("expected the forced rollback");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("force rollback");
        }
        assertThat(cache.isKnownClosed(rolledBack)).as("rolled back: never marked").isFalse();
        assertThat(countApplications(rolledBack)).isZero();
        assertThat(counterValue(rolledBack)).isZero();

        long committed = createEvent("OPEN", 2, 1);
        template.executeWithoutResult(status -> {
            service.acceptBatch(committed, List.of(1, 2));
            assertThat(cache.isKnownClosed(committed)).as("not marked before commit").isFalse();
        });
        assertThat(cache.isKnownClosed(committed)).as("marked after commit").isTrue();
        assertThat(countApplications(committed)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- T5

    @Test
    @DisplayName("T5: 앱 2대의 동시 접수·발급 뒤에도 초과 발급 0, 중복 0, 등급 규칙, CLOSED, PENDING 0이 유지된다")
    void two_apps_admission_and_issuance_keep_invariants() throws Exception {
        long eventId = createEvent("OPEN", 200, 50);
        CouponBatchAdmissionTransactionService batchAdmission = batchIssuanceApp.getBean(CouponBatchAdmissionTransactionService.class);
        CouponAdmissionTransactionService singleAdmission = singleIssuanceApp.getBean(CouponAdmissionTransactionService.class);
        CouponIssuanceProcessor batchIssuance = batchIssuanceApp.getBean(CouponIssuanceProcessor.class);
        CouponIssuanceProcessor singleIssuance = singleIssuanceApp.getBean(CouponIssuanceProcessor.class);

        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        AtomicBoolean admissionsDone = new AtomicBoolean(false);

        List<Callable<Void>> admissions = new ArrayList<>();
        // 앱 A(묶음 접수): 회원 1..250을 5명씩
        for (int batch = 0; batch < 50; batch++) {
            List<Integer> members = IntStream.rangeClosed(batch * 5 + 1, batch * 5 + 5).boxed().toList();
            admissions.add(() -> {
                try {
                    batchAdmission.acceptBatch(eventId, members).failures().values().forEach(failure -> {
                        if (!(failure instanceof CouponEventNotOpenException)) {
                            unexpected.add(failure);
                        }
                    });
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        // 앱 B(단건 접수): 회원 251..500
        for (int member = 251; member <= 500; member++) {
            int memberId = member;
            admissions.add(() -> {
                try {
                    singleAdmission.acceptNewOrExisting(eventId, memberId);
                } catch (CouponEventNotOpenException ended) {
                    // 마감 뒤 요청: 기대된 결과
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<?>> issuers = List.of(
                    pool.submit(() -> drain(batchIssuance, eventId, admissionsDone, unexpected)),
                    pool.submit(() -> drain(singleIssuance, eventId, admissionsDone, unexpected)));
            List<Future<Void>> admissionFutures = new ArrayList<>();
            for (Callable<Void> admission : admissions) {
                admissionFutures.add(pool.submit(admission));
            }
            for (Future<Void> future : admissionFutures) {
                future.get(120, TimeUnit.SECONDS);
            }
            admissionsDone.set(true);
            for (Future<?> issuer : issuers) {
                issuer.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        assertThat(scalarLong("SELECT COUNT(*) FROM coupon_award WHERE event_id=" + eventId)).isEqualTo(200L);
        assertThat(scalarLong("SELECT COUNT(*) FROM (SELECT member_id FROM coupon_award WHERE event_id=" + eventId
                + " GROUP BY member_id HAVING COUNT(*) > 1) d")).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM coupon_application a LEFT JOIN coupon_award w ON w.application_id=a.id "
                + "WHERE a.event_id=" + eventId + " AND a.status='ISSUED' AND w.id IS NULL")).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM coupon_application a JOIN coupon_award w ON w.application_id=a.id "
                + "WHERE a.event_id=" + eventId + " AND ((a.acceptance_sequence <= 50 AND w.tier <> 'HIGH') "
                + "OR (a.acceptance_sequence > 50 AND w.tier <> 'NORMAL'))")).isZero();
        assertThat(scalarLong("SELECT issued_quantity FROM coupon_event WHERE id=" + eventId)).isEqualTo(200L);
        assertThat(scalarString("SELECT status FROM coupon_event WHERE id=" + eventId)).isEqualTo("CLOSED");
        assertThat(countByStatus(eventId, "PENDING")).isZero();
    }

    private static void drain(CouponIssuanceProcessor processor, long eventId, AtomicBoolean admissionsDone,
                              ConcurrentLinkedQueue<Throwable> unexpected) {
        try {
            while (true) {
                boolean worked = processor.processNext(eventId);
                if (!worked) {
                    if (admissionsDone.get() && countByStatusQuietly(eventId, "PENDING") == 0) {
                        return;
                    }
                    Thread.sleep(5);
                }
            }
        } catch (Throwable t) {
            unexpected.add(t);
        }
    }

    // ---------------------------------------------------------------- T6

    @Test
    @DisplayName("T6: SCHEDULED 행사의 첫 접수 2건이 동시에 와도 둘 다 접수되고 상태는 OPEN이다")
    void concurrent_first_admissions_handle_mark_open_race() throws Exception {
        long eventId = createEvent("SCHEDULED", 10, 3);
        CouponAdmissionTransactionService service = singleIssuanceApp.getBean(CouponAdmissionTransactionService.class);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int member = 1; member <= 2; member++) {
            int memberId = member;
            tasks.add(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    service.acceptNewOrExisting(eventId, memberId);
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        runConcurrently(2, tasks);

        assertThat(unexpected).isEmpty();
        assertThat(countApplications(eventId)).isEqualTo(2);
        assertThat(scalarString("SELECT status FROM coupon_event WHERE id=" + eventId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("T6b: MQ 준비 상태가 먼저 OPEN으로 전이해 V1의 markOpen이 0행이어도 V1 접수는 예외 없이 이어진다")
    void v1_admission_continues_when_readiness_opens_the_event_first() throws Exception {
        long eventId = createEvent("SCHEDULED", 10, 3);
        CouponAdmissionTransactionService service = singleIssuanceApp.getBean(CouponAdmissionTransactionService.class);
        Connection readiness = open(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // MQ 준비 상태 역할: event를 FOR UPDATE로 잡고 있다가 OPEN 전이를 커밋한다.
            readiness.createStatement().execute("SELECT * FROM coupon_event WHERE id=" + eventId + " FOR UPDATE");
            Future<Throwable> v1 = pool.submit(() -> {
                try {
                    service.acceptNewOrExisting(eventId, 1);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Thread.sleep(700);
            readiness.createStatement().executeUpdate("UPDATE coupon_event SET status='OPEN' WHERE id=" + eventId
                    + " AND status='SCHEDULED'");
            readiness.commit();

            assertThat(v1.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(countApplications(eventId)).isEqualTo(1);
            assertThat(scalarString("SELECT status FROM coupon_event WHERE id=" + eventId)).isEqualTo("OPEN");
        } finally {
            pool.shutdownNow();
            closeAll(readiness);
        }
    }

    // ---------------------------------------------------------------- T7

    @Test
    @DisplayName("T7: MQ 접수는 순번이 total을 넘은 뒤 도착한 유효 메시지도 PENDING으로 접수하고 발급 워커가 SOLD_OUT 처리한다")
    void mq_admission_after_close_still_maps_to_pending() throws Exception {
        long eventId = createEvent("OPEN", 3, 1);
        String token = UUID.randomUUID().toString();
        String version = insertMqControl(eventId, token);
        CouponMqAdmissionTransactionService mq = singleIssuanceApp.getBean(CouponMqAdmissionTransactionService.class);
        CouponIssuanceProcessor issuance = singleIssuanceApp.getBean(CouponIssuanceProcessor.class);

        // 순번이 total(3)을 넘는 메시지(4, 5번)까지 한 번에 접수된다. V1의 D1 규칙은 MQ에 적용되지 않는다.
        var firstBatch = IntStream.rangeClosed(1, 5)
                .mapToObj(member -> CouponMqIntegrationMessages.admit(eventId, member, UUID.randomUUID().toString(), version, token))
                .toList();
        assertThat(mq.accept(eventId, firstBatch).actions()).containsOnly(CouponMqAdmissionTransactionService.Action.ACK);
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo(5);
        while (issuance.processNext(eventId)) {
            // drain
        }
        assertThat(scalarString("SELECT status FROM coupon_event WHERE id=" + eventId)).isEqualTo("CLOSED");
        assertThat(countByStatus(eventId, "ISSUED")).isEqualTo(3);
        assertThat(countByStatus(eventId, "SOLD_OUT")).isEqualTo(2);

        // CLOSED 이후 도착한 유효 메시지: 여전히 PENDING으로 접수되고 발급 워커가 SOLD_OUT 처리한다.
        var late = CouponMqIntegrationMessages.admit(eventId, 6, UUID.randomUUID().toString(), version, token);
        assertThat(mq.accept(eventId, List.of(late)).actions()).containsOnly(CouponMqAdmissionTransactionService.Action.ACK);
        assertThat(scalarLong("SELECT acceptance_sequence FROM coupon_application WHERE event_id=" + eventId + " AND member_id=6"))
                .isEqualTo(6L);
        assertThat(scalarString("SELECT status FROM coupon_application WHERE event_id=" + eventId + " AND member_id=6"))
                .isEqualTo("PENDING");
        while (issuance.processNext(eventId)) {
            // drain
        }
        assertThat(scalarString("SELECT status FROM coupon_application WHERE event_id=" + eventId + " AND member_id=6"))
                .isEqualTo("SOLD_OUT");
        assertThat(scalarLong("SELECT COUNT(*) FROM coupon_award WHERE event_id=" + eventId)).isEqualTo(3L);
        assertThat(scalarLong("SELECT issued_quantity FROM coupon_event WHERE id=" + eventId)).isEqualTo(3L);
    }

    // ---------------------------------------------------------------- context

    private static AnnotationConfigApplicationContext newApp(boolean batchIssuance) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("coupon-mq");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("counter-split-test",
                Map.of("coupon.event-issuance.batch.enabled", String.valueOf(batchIssuance))));
        context.register(AppConfig.class);
        context.registerBean(com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponAdmissionTimingContext.class);
        context.registerBean(com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponAdmissionTransactionTimers.class);
        context.registerBean(com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponIssuanceTransactionTimers.class);
        context.registerBean(CouponEventClosureCache.class);
        context.registerBean(CouponAdmissionTransactionService.class);
        context.registerBean(CouponBatchAdmissionTransactionService.class);
        context.registerBean(com.jinddung2.givemeticon.domain.coupon.service.CouponEventIssuanceTransactionService.class);
        context.registerBean(com.jinddung2.givemeticon.domain.coupon.service.CouponEventIssuanceBatchTransactionService.class);
        context.registerBean(CouponMqAdmissionTransactionService.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackages = "com.jinddung2.givemeticon.domain.coupon.mapper")
    static class AppConfig {

        @Bean(destroyMethod = "close")
        DataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(URL);
            dataSource.setUsername(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setMaximumPoolSize(12);
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.jinddung2.givemeticon.domain.coupon.domain");
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            List<Resource> mappers = new ArrayList<>();
            for (String name : List.of("CouponEventMapper", "CouponEventAdmissionCounterMapper", "CouponApplicationMapper",
                    "CouponAwardMapper", "CouponMqControlMapper", "CouponMqAttemptMapper")) {
                ClassPathResource resource = new ClassPathResource("mapper/" + name + ".xml");
                if (resource.exists()) {
                    mappers.add(resource);
                }
            }
            factory.setMapperLocations(mappers.toArray(new Resource[0]));
            return factory.getObject();
        }
    }

    // ---------------------------------------------------------------- JDBC helpers

    private static Connection open(boolean shortLockTimeout) throws SQLException {
        Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        if (shortLockTimeout) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = 2");
            }
        }
        return connection;
    }

    private static void closeAll(Connection... connections) {
        for (Connection connection : connections) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // closed right after
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // see above
            }
        }
    }

    private static TransactionTemplate readCommittedTemplate(AnnotationConfigApplicationContext app) {
        TransactionTemplate template = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        template.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        return template;
    }

    private static void runConcurrently(int threads, List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<Void> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 행사와 그 접수 카운터 행을 만든다(행사를 만드는 쪽이 카운터 행도 함께 만든다는 ADR-006 구현 원칙). */
    private static long createEvent(String status, int total, int high) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO coupon_event (public_id, status, starts_at_utc, total_quantity, high_quantity, "
                    + "high_points, normal_points, settings_locked_at) VALUES ('ct-" + UUID.randomUUID() + "', '" + status
                    + "', TIMESTAMPADD(MINUTE, -5, UTC_TIMESTAMP(6)), " + total + ", " + high
                    + ", 1000, 100, UTC_TIMESTAMP(6))", Statement.RETURN_GENERATED_KEYS);
            long eventId;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                eventId = keys.getLong(1);
            }
            statement.executeUpdate("INSERT INTO coupon_event_admission_counter (event_id, next_acceptance_sequence) VALUES ("
                    + eventId + ", 0)");
            return eventId;
        }
    }

    private static void insertPendingApplication(long eventId, int memberId, long sequence) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO coupon_application (public_request_id, event_id, member_id, acceptance_sequence, "
                    + "status) VALUES ('" + UUID.randomUUID() + "', " + eventId + ", " + memberId + ", " + sequence + ", 'PENDING')");
            statement.executeUpdate("UPDATE coupon_event_admission_counter SET next_acceptance_sequence = " + sequence
                    + " WHERE event_id=" + eventId);
        }
    }

    /** MQ 준비 상태가 커밋한 것과 같은 모양의 control 행(고정 OPEN 토큰, 일시중지 해제)을 만들고 config version을 돌려준다. */
    private static String insertMqControl(long eventId, String token) throws SQLException {
        CouponEvent event = new CouponEvent();
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT public_id, starts_at_utc, total_quantity, high_quantity, high_points, "
                     + "normal_points, settings_locked_at FROM coupon_event WHERE id=" + eventId)) {
            rs.next();
            Map<String, Object> fields = Map.of("id", eventId, "publicId", rs.getString(1),
                    "startsAtUtc", rs.getObject(2, LocalDateTime.class), "totalQuantity", rs.getInt(3),
                    "highQuantity", rs.getInt(4), "highPoints", rs.getInt(5), "normalPoints", rs.getInt(6),
                    "settingsLockedAt", rs.getObject(7, LocalDateTime.class));
            fields.forEach((name, value) -> ReflectionTestUtils.setField(event, name, value));
            String version = CouponMqControl.version(event);
            statement.executeUpdate("INSERT INTO coupon_mq_control (event_id, config_version, open_token, verified_at, ingest_paused) "
                    + "VALUES (" + eventId + ", '" + version + "', '" + token + "', UTC_TIMESTAMP(6), FALSE)");
            return version;
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long countApplications(long eventId) throws SQLException {
        return scalarLong("SELECT COUNT(*) FROM coupon_application WHERE event_id=" + eventId);
    }

    private static long countByStatus(long eventId, String status) throws SQLException {
        return scalarLong("SELECT COUNT(*) FROM coupon_application WHERE event_id=" + eventId + " AND status='" + status + "'");
    }

    private static long countByStatusQuietly(long eventId, String status) {
        try {
            return countByStatus(eventId, status);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long counterValue(long eventId) throws SQLException {
        return scalarLong("SELECT next_acceptance_sequence FROM coupon_event_admission_counter WHERE event_id=" + eventId);
    }

    /** 신청이 정확히 expected건이고 acceptance_sequence가 1..expected를 빈틈없이 한 번씩 갖는지 확인한다. */
    private static void assertSequencesExactly(long eventId, int expected) throws SQLException {
        List<Long> sequences = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT acceptance_sequence FROM coupon_application WHERE event_id="
                     + eventId + " ORDER BY acceptance_sequence")) {
            while (rs.next()) {
                sequences.add(rs.getLong(1));
            }
        }
        assertThat(sequences).hasSize(expected);
        assertThat(new HashSet<>(sequences)).hasSize(expected);
        assertThat(sequences).isEqualTo(LongStreamRange.of(1, expected));
    }

    private static final class LongStreamRange {
        static List<Long> of(long fromInclusive, long toInclusive) {
            return java.util.stream.LongStream.rangeClosed(fromInclusive, toInclusive).boxed().collect(Collectors.toList());
        }
    }
}
