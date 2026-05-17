package com.jinddung2.givemeticon.domain.item.mapper;

import com.jinddung2.givemeticon.domain.item.controller.dto.PopularItemDto;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ItemMapper {

    int saveOrUpdate(Item item);

    Optional<Item> findById(int itemId);

    List<PopularItemDto> findPopularItemsOrderByLikeCount(@Param("limit") int limit);

    List<PopularItemDto> findPopularItemsOrderByViewCount(@Param("limit") int limit);

}
