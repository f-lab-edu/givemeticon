package com.jinddung2.givemeticon.domain.sale.mapper;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SaleMapper {

    int save(Sale itemVariant);

    boolean existsByBarcode(String barcode);
}
