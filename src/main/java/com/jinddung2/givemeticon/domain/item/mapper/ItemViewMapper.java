package com.jinddung2.givemeticon.domain.item.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemViewMapper {

    void save(int itemId, int userId);

}
