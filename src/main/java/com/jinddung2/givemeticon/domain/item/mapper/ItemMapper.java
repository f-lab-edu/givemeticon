package com.jinddung2.givemeticon.domain.item.mapper;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface ItemMapper {

    int saveOrUpdate(Item item);

    Optional<Item> findById(int itemId);

}
