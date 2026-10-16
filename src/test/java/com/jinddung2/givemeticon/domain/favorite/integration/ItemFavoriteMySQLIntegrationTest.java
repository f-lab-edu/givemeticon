package com.jinddung2.givemeticon.domain.favorite.integration;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.AlreadyPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.NotPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMapper;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteService;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Uses only the disposable issue-184 MySQL schema. No test-managed rollback or mocked mapper. */
@Tag("integration")
class ItemFavoriteMySQLIntegrationTest {
    private static AnnotationConfigApplicationContext context;
    private static ItemFavoriteService service;
    private static JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13384/favorite_consistency_test",
                    "root", "favorite-test-only");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.getTypeAliasRegistry().registerAlias("ItemFavorite", ItemFavorite.class);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/ItemFavoriteMapper.xml"),
                    new ClassPathResource("mapper/ItemFavoriteMetaMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        ItemFavoriteService itemFavoriteService(SqlSessionTemplate template) {
            return new ItemFavoriteService(template.getMapper(ItemFavoriteMapper.class));
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(ItemFavoriteService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("favorite_consistency_test");
        jdbc.execute("DROP TABLE IF EXISTS item_favorite");
        jdbc.execute("DROP TABLE IF EXISTS item_favorite_meta");
        jdbc.execute("DROP TABLE IF EXISTS item");
        jdbc.execute("CREATE TABLE item (id INT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS item_favorite (id INT PRIMARY KEY AUTO_INCREMENT, "
                + "user_id INT, item_id INT, is_favorite BOOLEAN NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS item_favorite_meta (id INT PRIMARY KEY AUTO_INCREMENT, item_id INT NOT NULL, "
                + "like_count INT NOT NULL, view_count INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
        try (Connection connection = context.getBean(DataSource.class).getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V20261015__add_item_favorite_member_item_unique.sql"));
        }
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_meta_update");
        jdbc.update("DELETE FROM item_favorite");
        jdbc.update("DELETE FROM item_favorite_meta");
        jdbc.update("DELETE FROM item");
        jdbc.update("INSERT INTO item VALUES (1)");
        jdbc.update("INSERT INTO item_favorite_meta(item_id, like_count, view_count) VALUES(1, 0, 0)");
    }

    @Test
    void add_activatesFavorite() {
        service.insertFavorite(7, 1);
        assertActive(1);
    }

    @Test
    void cancel_deactivatesFavorite() {
        service.insertFavorite(7, 1);
        service.cancelItemFavorite(7, 1);
        assertActive(0);
    }

    @Test
    void readd_activatesExistingRow() {
        service.insertFavorite(7, 1);
        service.cancelItemFavorite(7, 1);
        service.insertFavorite(7, 1);
        assertActive(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite", Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatedCancel_staysInactive() {
        service.insertFavorite(7, 1);
        service.cancelItemFavorite(7, 1);
        service.cancelItemFavorite(7, 1);
        assertActive(0);
    }

    @Test
    void concurrentSameMember_onlyOneAddSucceeds() throws Exception {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        concurrently(20, i -> {
            try {
                service.insertFavorite(7, 1);
                success.incrementAndGet();
            } catch (AlreadyPushItemFavorite e) {
                duplicates.incrementAndGet();
            }
        });
        assertThat(success.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(19);
        assertActive(1);
    }

    @Test
    void concurrentMembers_finalActiveCountMatches() throws Exception {
        concurrently(20, i -> service.insertFavorite(i + 1, 1));
        assertActive(20);
        concurrently(20, i -> service.cancelItemFavorite(i + 1, 1));
        assertActive(0);
    }

    @Test
    void concurrentAddAndCancel_followsSerializedTransitions() throws Exception {
        // A serial add changes false/absent -> true; cancel changes true -> false or is a no-op.
        // Rejected active adds and absent cancels are allowed, every unexpected error fails the test.
        for (int round = 0; round < 5; round++) {
            concurrently(20, i -> {
                if (i % 2 == 0) {
                    try { service.insertFavorite(7, 1); }
                    catch (AlreadyPushItemFavorite expected) { }
                } else {
                    try { service.cancelItemFavorite(7, 1); }
                    catch (NotPushItemFavorite expected) { }
                }
            });
            assertThat(activeCount()).isBetween(0, 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void databaseUniqueConstraint_rejectsDuplicateLogicalKey() {
        service.insertFavorite(7, 1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO item_favorite(user_id,item_id,is_favorite) VALUES(7,1,TRUE)"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertActive(1);
    }

    @Test
    void absentFavoriteCancel_preservesExistingException() {
        assertThatThrownBy(() -> service.cancelItemFavorite(7, 1)).isInstanceOf(NotPushItemFavorite.class);
        assertActive(0);
    }

    @Test
    void getMyFavorite_mapsItemIdAndExcludesInactiveRows() {
        service.insertFavorite(7, 1);
        assertThat(service.getMyFavorite(7)).singleElement().satisfies(favorite -> {
            assertThat(favorite.getItemId()).isEqualTo(1);
            assertThat(favorite.getUserId()).isEqualTo(7);
        });
        service.cancelItemFavorite(7, 1);
        assertThat(service.getMyFavorite(7)).isEmpty();
    }

    @Test
    void maintenanceReconciliation_repairsActiveCountsIncludingZeroAndIsIdempotent() throws Exception {
        jdbc.update("INSERT INTO item VALUES(2)");
        jdbc.update("INSERT INTO item_favorite_meta(item_id,like_count,view_count) VALUES(2,99,42)");
        jdbc.update("INSERT INTO item_favorite(user_id,item_id,is_favorite) VALUES(7,1,TRUE),(8,1,FALSE),(9,1,TRUE)");
        jdbc.update("UPDATE item_favorite_meta SET like_count=10 WHERE item_id=1");
        for (int run = 0; run < 2; run++) {
            try (Connection connection = context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                        "scripts/maintenance/reconcile-item-favorite-count.sql"));
                connection.commit();
            }
            assertActive(2);
            assertThat(jdbc.queryForObject("SELECT like_count FROM item_favorite_meta WHERE item_id=2", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT view_count FROM item_favorite_meta WHERE item_id=2", Integer.class)).isEqualTo(42);
        }
    }

    @Test
    void databaseKeyConstraint_rejectsNullLogicalKey() {
        assertThatThrownBy(() -> {
            try (Connection connection = context.getBean(DataSource.class).getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO item_favorite(user_id,item_id,is_favorite) VALUES(NULL,1,TRUE)");
            }
        }).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(3819));
        assertActive(0);
    }

    private int activeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite WHERE item_id=1 AND is_favorite=TRUE", Integer.class);
    }

    private void assertActive(int active) {
        assertThat(activeCount()).isEqualTo(active);
    }

    private interface Operation { void run(int index); }

    private void concurrently(int threads, Operation operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    operation.run(index);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
