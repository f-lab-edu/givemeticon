package com.jinddung2.givemeticon.domain.favorite.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ItemFavoriteMetaMapper {

    void save(@Param("itemId") int itemId);

}
