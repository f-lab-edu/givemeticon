package com.jinddung2.givemeticon.item.infrasturcture.mapper;

import com.jinddung2.givemeticon.item.domain.Item;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemMapper {

    int save(Item item);

}
