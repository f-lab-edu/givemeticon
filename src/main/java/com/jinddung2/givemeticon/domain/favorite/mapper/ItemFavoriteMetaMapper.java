package com.jinddung2.givemeticon.domain.favorite.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemFavoriteMetaMapper {

    void save(int itemId);

}
