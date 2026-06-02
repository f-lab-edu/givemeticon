package com.jinddung2.givemeticon.domain.item.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ItemMapperSqlTest {

    private static final Path ITEM_MAPPER_XML = Path.of("src/main/resources/mapper/ItemMapper.xml");

    @Test
    @DisplayName("인기 아이템 조회 SQL은 GROUP BY와 COUNT를 사용하지 않는다.")
    void popularItemSql_Does_Not_Use_Group_By_Or_Count() throws Exception {
        String mapperXml = Files.readString(ITEM_MAPPER_XML);
        String likeSql = extractSelect(mapperXml, "findPopularItemsOrderByLikeCount");
        String viewSql = extractSelect(mapperXml, "findPopularItemsOrderByViewCount");

        assertThat(likeSql.toUpperCase()).doesNotContain("GROUP BY", "COUNT(");
        assertThat(viewSql.toUpperCase()).doesNotContain("GROUP BY", "COUNT(");
        assertThat(likeSql).contains("item_favorite_meta", "m.like_count", "ORDER BY m.like_count DESC, m.item_id DESC");
        assertThat(viewSql).contains("item_favorite_meta", "m.view_count", "ORDER BY m.view_count DESC, m.item_id DESC");
    }

    private String extractSelect(String mapperXml, String id) {
        int start = mapperXml.indexOf("<select id=\"" + id + "\"");
        int end = mapperXml.indexOf("</select>", start);
        return mapperXml.substring(start, end);
    }
}
