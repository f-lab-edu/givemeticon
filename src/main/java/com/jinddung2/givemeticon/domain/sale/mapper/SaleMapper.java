package com.jinddung2.givemeticon.domain.sale.mapper;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface SaleMapper {

    int save(Sale itemVariant);

    boolean existsByBarcode(String barcode);

    Optional<Sale> findById(int saleId);
}
