package com.jinddung2.givemeticon.domain.favorite.integration;

import com.jinddung2.givemeticon.common.config.controller.HandlerAdapterCustomConfig;
import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMapper;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMetaMapper;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteAggregationService;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteService;
import com.jinddung2.givemeticon.domain.item.controller.ItemController;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Disposable MySQL only. Real proxy/SQL; no test transaction or mocked database calls. */
@Tag("integration")
class ItemFavoriteAggregationMySQLIntegrationTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:13385/favorite_batch_test";
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ItemFavoriteService favorites;
    private static ItemFavoriteAggregationService aggregation;
    private static ItemMapper items;
    private static MockMvc api;
    private static final PublishBarrier barrier = new PublishBarrier();
    private final List<CompletableFuture<Boolean>> runs = new ArrayList<>();

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(URL);
            source.setUsername("root");
            source.setPassword("favorite-test-only");
            source.setMaximumPoolSize(8);
            source.setMinimumIdle(0);
            source.setConnectionTimeout(10_000);
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        SqlSessionTemplate template(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.getTypeAliasRegistry().registerAlias("ItemFavorite", ItemFavorite.class);
            configuration.getTypeAliasRegistry().registerAlias("Item", Item.class);
            factory.setConfiguration(configuration);
            factory.setPlugins(barrier);
            factory.setMapperLocations(new ClassPathResource("mapper/ItemFavoriteMapper.xml"),
                    new ClassPathResource("mapper/ItemFavoriteMetaMapper.xml"),
                    new ClassPathResource("mapper/ItemMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        ItemFavoriteService favorites(SqlSessionTemplate template) {
            return new ItemFavoriteService(template.getMapper(ItemFavoriteMapper.class));
        }

        @Bean
        ItemFavoriteAggregationService aggregation(SqlSessionTemplate template) {
            return new ItemFavoriteAggregationService(template.getMapper(ItemFavoriteMetaMapper.class));
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    public static class PublishBarrier implements Interceptor {
        final AtomicInteger calls = new AtomicInteger();
        int pauseAt;
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        void reset(int pauseAt) {
            this.pauseAt = pauseAt;
            calls.set(0);
            reached = new CountDownLatch(1);
            resume = new CountDownLatch(1);
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith(".replaceLikeCounts") && calls.incrementAndGet() == pauseAt) {
                reached.countDown();
                if (!resume.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test publish barrier timed out");
            }
            return invocation.proceed();
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        // Dedicated unpooled observer: every assertion sees the DB outside the service transaction.
        jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, "root", "favorite-test-only"));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("favorite_batch_test");
        jdbc.execute("DROP TABLE IF EXISTS item_favorite_aggregation_lock");
        jdbc.execute("DROP TABLE IF EXISTS item_favorite");
        jdbc.execute("DROP TABLE IF EXISTS item_favorite_meta");
        jdbc.execute("DROP TABLE IF EXISTS item");
        jdbc.execute("CREATE TABLE item (id INT PRIMARY KEY, brand_id INT NOT NULL, name VARCHAR(50), price INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE item_favorite (id INT PRIMARY KEY AUTO_INCREMENT, user_id INT NOT NULL, "
                + "item_id INT NOT NULL, is_favorite BOOLEAN NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE item_favorite_meta (id INT PRIMARY KEY AUTO_INCREMENT, item_id INT NOT NULL, "
                + "like_count INT NOT NULL, view_count INT NOT NULL) ENGINE=InnoDB");
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V20261015__add_item_favorite_member_item_unique.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V20261015_2__add_favorite_aggregation_lock.sql"));
        }
        context = new AnnotationConfigApplicationContext(Config.class);
        favorites = context.getBean(ItemFavoriteService.class);
        aggregation = context.getBean(ItemFavoriteAggregationService.class);
        SqlSessionTemplate template = context.getBean(SqlSessionTemplate.class);
        items = template.getMapper(ItemMapper.class);
        ItemService service = new ItemService(items, null, template.getMapper(ItemFavoriteMetaMapper.class));
        api = MockMvcBuilders.standaloneSetup(new ItemController(null, service)).build();
        RequestMappingHandlerAdapter adapter = api.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerAdapter.class);
        new HandlerAdapterCustomConfig(adapter).postConstruct();
    }

    @AfterAll
    static void close() {
        barrier.resume.countDown();
        if (context != null) context.close();
    }

    @BeforeEach
    void reset() {
        barrier.reset(0);
        jdbc.execute("DROP TRIGGER IF EXISTS fail_meta_publish");
        jdbc.update("DELETE FROM item_favorite");
        jdbc.update("DELETE FROM item_favorite_meta");
        jdbc.update("DELETE FROM item");
        seedItems(2, 7);
    }

    private void seedItems(int count, int oldCount) {
        List<Object[]> itemRows = new ArrayList<>();
        List<Object[]> metaRows = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            itemRows.add(new Object[]{id, 1, "item" + id, 1000});
            metaRows.add(new Object[]{id, oldCount, 40});
        }
        jdbc.batchUpdate("INSERT INTO item(id,brand_id,name,price) VALUES(?,?,?,?)", itemRows);
        jdbc.batchUpdate("INSERT INTO item_favorite_meta(item_id,like_count,view_count) VALUES(?,?,?)", metaRows);
    }

    @Test
    void addCancelReadd_changesOnlyOriginalEvenWithoutMeta() {
        jdbc.update("DELETE FROM item_favorite_meta WHERE item_id=1");
        favorites.insertFavorite(7, 1);
        assertThat(active(1)).isEqualTo(1);
        favorites.cancelItemFavorite(7, 1);
        favorites.cancelItemFavorite(7, 1);
        assertThat(active(1)).isZero();
        favorites.insertFavorite(7, 1);
        assertThat(active(1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite_meta WHERE item_id=1", Integer.class)).isZero();
        assertThat(meta(2)).isEqualTo(7);
    }

    @Test
    void snapshot_matchesActiveCountsAndResetsZeroAndPreservesViews() {
        favorites.insertFavorite(7, 1);
        favorites.insertFavorite(8, 1);
        favorites.insertFavorite(9, 2);
        favorites.cancelItemFavorite(9, 2);
        assertThat(aggregation.refreshLikeCounts()).isTrue();
        assertThat(meta(1)).isEqualTo(active(1)).isEqualTo(2);
        assertThat(meta(2)).isEqualTo(active(2)).isZero();
        assertThat(jdbc.queryForList("SELECT view_count FROM item_favorite_meta", Integer.class)).containsOnly(40);
    }

    @Test
    void missingMeta_isCreatedIncludingZeroFavorites() {
        jdbc.update("DELETE FROM item_favorite_meta");
        favorites.insertFavorite(7, 1);
        assertThat(aggregation.refreshLikeCounts()).isTrue();
        assertThat(meta(1)).isEqualTo(1);
        assertThat(meta(2)).isZero();
    }

    @Test
    void changesAfterSnapshot_areIncludedOnlyInNextRun() throws Exception {
        favorites.insertFavorite(7, 1);
        barrier.reset(1);
        CompletableFuture<Boolean> run = startRun();
        try {
            assertThat(barrier.reached.await(10, TimeUnit.SECONDS)).isTrue();
            favorites.cancelItemFavorite(7, 1);
            favorites.insertFavorite(8, 2);
            assertThat(active(1)).isZero();
            assertThat(active(2)).isEqualTo(1);
        } finally {
            barrier.resume.countDown();
        }
        assertThat(run.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(meta(1)).isEqualTo(1);
        assertThat(meta(2)).isZero();
        assertThat(aggregation.refreshLikeCounts()).isTrue();
        assertThat(meta(1)).isZero();
        assertThat(meta(2)).isEqualTo(1);
    }

    @Test
    void overlappingRun_isSkippedUntilOwnerCommits() throws Exception {
        barrier.reset(1);
        CompletableFuture<Boolean> owner = startRun();
        try {
            assertThat(barrier.reached.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(startRun().get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            barrier.resume.countDown();
        }
        assertThat(owner.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(aggregation.refreshLikeCounts()).isTrue();
    }

    private void seedMultipleChunks() {
        jdbc.update("DELETE FROM item_favorite_meta");
        jdbc.update("DELETE FROM item");
        seedItems(501, 7);
        jdbc.update("INSERT INTO item_favorite(user_id,item_id,is_favorite) SELECT 7,id,TRUE FROM item");
    }

    @Test
    void publication_isAtomicToPopularApiAcrossChunks() throws Exception {
        seedMultipleChunks();
        barrier.reset(2); // First 500 updates have really executed, but have not committed.
        CompletableFuture<Boolean> run = startRun();
        try {
            assertThat(barrier.reached.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(items.findPopularItemsOrderByLikeCount(50)).allSatisfy(item -> assertThat(item.getLikeCount()).isEqualTo(7));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite_meta WHERE like_count=7", Integer.class)).isEqualTo(501);
        } finally {
            barrier.resume.countDown();
        }
        assertThat(run.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite_meta WHERE like_count=1", Integer.class)).isEqualTo(501);
    }

    @Test
    void databaseFailureAfterFirstChunk_keepsPreviousSnapshotAndReleasesLock() throws Exception {
        seedMultipleChunks();
        jdbc.execute("CREATE TRIGGER fail_meta_publish BEFORE UPDATE ON item_favorite_meta FOR EACH ROW "
                + "BEGIN IF NEW.item_id=501 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected batch failure'; END IF; END");
        barrier.reset(2);
        CompletableFuture<Boolean> run = startRun();
        try {
            assertThat(barrier.reached.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(items.findPopularItemsOrderByLikeCount(50)).allSatisfy(item -> assertThat(item.getLikeCount()).isEqualTo(7));
        } finally {
            barrier.resume.countDown();
        }
        assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS)).hasRootCauseMessage("injected batch failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite_meta WHERE like_count=7", Integer.class)).isEqualTo(501);
        jdbc.execute("DROP TRIGGER fail_meta_publish");
        assertThat(aggregation.refreshLikeCounts()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite_meta WHERE like_count=1", Integer.class)).isEqualTo(501);
    }

    @Test
    void rerun_isIdempotent() {
        favorites.insertFavorite(7, 1);
        assertThat(aggregation.refreshLikeCounts()).isTrue();
        List<java.util.Map<String, Object>> first = jdbc.queryForList("SELECT item_id,like_count,view_count FROM item_favorite_meta ORDER BY item_id");
        assertThat(aggregation.refreshLikeCounts()).isTrue();
        assertThat(jdbc.queryForList("SELECT item_id,like_count,view_count FROM item_favorite_meta ORDER BY item_id")).isEqualTo(first);
    }

    @Test
    void popularApi_keepsContractAndReadsLastSnapshot() throws Exception {
        favorites.insertFavorite(7, 1);
        api.perform(get("/api/v1/items/popular").param("sort", "LIKE").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].likeCount").value(7));
        assertThat(aggregation.refreshLikeCounts()).isTrue();
        api.perform(get("/api/v1/items/popular").param("sort", "LIKE").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1)).andExpect(jsonPath("$.data[0].likeCount").value(1))
                .andExpect(jsonPath("$.data[0].viewCount").value(40));
    }

    private CompletableFuture<Boolean> startRun() {
        CompletableFuture<Boolean> run = CompletableFuture.supplyAsync(aggregation::refreshLikeCounts);
        runs.add(run);
        return run;
    }

    @AfterEach
    void awaitBatchCleanup() throws Exception {
        barrier.resume.countDown();
        for (CompletableFuture<Boolean> run : runs) {
            run.handle((result, failure) -> null).get(30, TimeUnit.SECONDS);
        }
    }

    private int active(int itemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM item_favorite WHERE item_id=? AND is_favorite=TRUE", Integer.class, itemId);
    }

    private int meta(int itemId) {
        return jdbc.queryForObject("SELECT like_count FROM item_favorite_meta WHERE item_id=?", Integer.class, itemId);
    }
}
