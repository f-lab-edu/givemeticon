package com.jinddung2.givemeticon.domain.sale.mapper;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface SaleMapper {

    int save(Sale itemVariant);

    void update(Sale sale);

    boolean existsByBarcode(String barcode);

    Optional<Sale> findById(int saleId);

    List<Sale> findNotBoughtSalesByItemId(int itemId);

    List<Sale> findMySalesBySellerId(int userId);

    List<Sale> findMySales(@Param("pageInfo") Map<String, Object> pageInfo);
}
