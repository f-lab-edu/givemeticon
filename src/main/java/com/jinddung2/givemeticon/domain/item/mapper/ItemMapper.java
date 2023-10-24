package com.jinddung2.givemeticon.domain.item.mapper;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemMapper {

    int save(Item item);

}
