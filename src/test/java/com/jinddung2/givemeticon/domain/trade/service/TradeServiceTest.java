package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.mapper.TradeMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.STANDARD;
import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.WEEKLY_DISCOUNT;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @InjectMocks
    TradeService tradeService;

    @Mock
    TradeMapper tradeMapper;

    Trade trade;
    int saleId, buyerId, price;

    @BeforeEach
    void setUp() {
        saleId = 10;
        buyerId = 20;
        price = 10000;
        trade = Trade.builder()
                .saleId(saleId)
                .buyerId(buyerId)
                .salePrice(new BigDecimal(price))
                .build();
    }

    @Test
    @DisplayName("10% 할인된 가격으로 거래 데이터 저장한다.")
    void save_10PER_Discount_Trade() {
        double discountRate = STANDARD.getDiscountRate();
        BigDecimal salePrice = BigDecimal.valueOf(price);

        tradeService.save(trade, 8L);

        BigDecimal discount = salePrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal result = salePrice.subtract(discount);

        Assertions.assertEquals(result, trade.getSalePrice());
        Mockito.verify(tradeMapper).save(trade);
    }

    @Test
    @DisplayName("15% 할인된 가격으로 거래 데이터 저장한다.")
    void save_15PER_Discount_Trade() {
        double discountRate = WEEKLY_DISCOUNT.getDiscountRate();
        BigDecimal salePrice = BigDecimal.valueOf(price);

        tradeService.save(trade, 7L);

        BigDecimal discount = salePrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal result = salePrice.subtract(discount);

        Assertions.assertEquals(result, trade.getSalePrice());
        Mockito.verify(tradeMapper).save(trade);
    }
}