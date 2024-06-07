package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.user.domain.User;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class SaleFixture {

    public static Sale createSaleFixture(User buyer, Item item) {
        LocalDateTime nowTime = LocalDateTime.now();
        LocalDate nowDate = LocalDate.of(nowTime.getYear(), nowTime.getMonth(), nowTime.getDayOfMonth()).plusMonths(1);
        return Sale.builder()
                .id(6)
                .itemId(item.getId())
                .sellerId(buyer.getId())
                .barcode("012345678901")
                .expirationDate(nowDate.plusMonths(1))
                .isBought(false)
                .isBoughtDate(Date.valueOf(nowDate))
                .createdDate(nowTime)
                .updatedDate(nowTime)
                .build();
    }

    public static Sale createSaleFixture(int id, User buyer, Item item) {
        LocalDateTime nowTime = LocalDateTime.now();
        LocalDate nowDate = LocalDate.of(nowTime.getYear(), nowTime.getMonth(), nowTime.getDayOfMonth()).plusMonths(1);
        return Sale.builder()
                .id(id)
                .itemId(item.getId())
                .sellerId(buyer.getId())
                .barcode("012345678901")
                .expirationDate(nowDate.plusMonths(1))
                .isBought(false)
                .isBoughtDate(Date.valueOf(nowDate))
                .createdDate(nowTime)
                .updatedDate(nowTime)
                .build();
    }

    public static Sale createBoughtSaleFixture(User buyer, Item item) {
        LocalDateTime nowTime = LocalDateTime.now();
        LocalDate nowDate = LocalDate.of(nowTime.getYear(), nowTime.getMonth(), nowTime.getDayOfMonth()).plusMonths(1);
        return Sale.builder()
                .id(6)
                .itemId(item.getId())
                .sellerId(buyer.getId())
                .barcode("012345678901")
                .expirationDate(nowDate.plusMonths(1))
                .isBought(true)
                .isBoughtDate(Date.valueOf(nowDate))
                .createdDate(nowTime)
                .updatedDate(nowTime)
                .build();
    }
}
