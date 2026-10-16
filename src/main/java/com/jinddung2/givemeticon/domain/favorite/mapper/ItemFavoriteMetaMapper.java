package com.jinddung2.givemeticon.domain.favorite.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavoriteCount;
import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavoriteAggregationState;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ItemFavoriteMetaMapper {

    void save(@Param("itemId") int itemId);

    void increaseViewCount(@Param("itemId") int itemId);

    ItemFavoriteAggregationState lockAggregation();

    int recordAggregationSuccess(@Param("snapshotAt") LocalDateTime snapshotAt,
                                 @Param("itemCount") int itemCount);

    List<ItemFavoriteCount> countActiveFavorites();

    void replaceLikeCounts(@Param("counts") List<ItemFavoriteCount> counts);

}
