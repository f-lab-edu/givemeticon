package com.jinddung2.givemeticon.domain.item.mapper;

import com.jinddung2.givemeticon.domain.item.domain.ItemVariant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemVariantMapper {

    int save(ItemVariant itemVariant);

    boolean existsByBarcode(String barcode);
}
