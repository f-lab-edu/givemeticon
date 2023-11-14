package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.NotFoundTradeException;
import com.jinddung2.givemeticon.domain.trade.exception.NotMatchBuyOwnership;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jinddung2.givemeticon.common.utils.PaginationUtil.makePagingParamMap;
import static com.jinddung2.givemeticon.common.utils.constants.PageSize.TRADE;
import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.STANDARD;
import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.WEEKLY_DISCOUNT;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @InjectMocks
    TradeService tradeService;

    @Mock
    TradeMapper tradeMapper;

    Trade trade;
    int saleId, buyerId, price, tradeId;

    @BeforeEach
    void setUp() {
        saleId = 10;
        buyerId = 20;
        tradeId = 30;
        price = 10000;
        trade = Trade.builder()
                .id(tradeId)
                .saleId(saleId)
                .buyerId(buyerId)
                .isUsed(false)
                .tradePrice(BigDecimal.valueOf(price))
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

        Assertions.assertEquals(result, trade.getTradePrice());
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

        Assertions.assertEquals(result, trade.getTradePrice());
        Mockito.verify(tradeMapper).save(trade);
    }

    @Test
    @DisplayName("거래 단건조회에 성공한다.")
    void get_Trade_By_Id() {
        Mockito.when(tradeMapper.findById(trade.getId())).thenReturn(Optional.of(trade));

        Trade result = tradeService.getTrade(trade.getId());

        Assertions.assertEquals(trade.getId(), result.getId());
        Assertions.assertEquals(trade.getTradePrice(), result.getTradePrice());
    }

    @Test
    @DisplayName("판매자 id가 일치하는 거래 단건조회에 성공한다.")
    void get_Trade_By_SaleId() {
        Mockito.when(tradeMapper.findBySaleId(trade.getSaleId())).thenReturn(Optional.of(trade));

        Optional<Trade> result = tradeService.getTradeBySaleId(trade.getSaleId());

        Assertions.assertTrue(result.isPresent());
        Assertions.assertFalse(result.get().isUsed());
    }

    @Test
    @DisplayName("판매자 id가 일치하는 거래 단건조회 했는데 데이터가 없다.")
    void get_Trade_By_SaleId_Not_Found_Trade() {
        Mockito.when(tradeMapper.findBySaleId(trade.getSaleId())).thenReturn(Optional.empty());

        Optional<Trade> result = tradeService.getTradeBySaleId(trade.getSaleId());

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("거래번호가 없어 단건조회에 실패한다한다.")
    void get_Trade_ById_Fail_Not_Found_Trade() {
        Mockito.when(tradeMapper.findById(trade.getId())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundTradeException.class,
                () -> tradeService.getTrade(trade.getId()));
    }

    @Test
    @DisplayName("미사용된 아이템을 조회한다.")
    void getMyUnusedItemHistory() {
        int page = 0;
        Map<String, Object> pageInfo = makePagingParamMap(buyerId, page, TRADE.getSize());
        boolean orderByBoughtDate = false;
        boolean orderByExpiredDate = false;

        List<Trade> tradeList = new ArrayList<>();
        tradeList.add(trade);
        Mockito.when(tradeMapper.findMyBoughtGifticon(pageInfo, orderByBoughtDate, orderByExpiredDate)).thenReturn(tradeList);

        List<Trade> result = tradeService.getMyUnusedItemHistory(buyerId, orderByBoughtDate, orderByExpiredDate, page);

        Assertions.assertEquals(result.size(), tradeList.size());

    }

    @Test
    @DisplayName("구매 확정에 성공한다.")
    void buy_Confirmation_Success() {
        Mockito.when(tradeMapper.findById(tradeId)).thenReturn(Optional.of(trade));

        tradeService.buyConfirmation(trade.getId(), buyerId);

        Mockito.verify(tradeMapper).updateIsUsedAndIsUsedDate(trade.getId());
        Assertions.assertTrue(trade.isUsed());
    }

    @Test
    @DisplayName("이미 구매 확정인 상태라서 구매 확정을 취소한다.")
    void buy_Confirmation_Fail_Already_Buy_Confirmation() {
        Trade fakeTrade = Trade.builder().isUsed(true).buyerId(buyerId).build();
        Mockito.when(tradeMapper.findById(tradeId)).thenReturn(Optional.of(fakeTrade));

        tradeService.buyConfirmation(trade.getId(), buyerId);

        Mockito.verify(tradeMapper).updateIsUsedAndIsUsedDate(trade.getId());
        Assertions.assertFalse(trade.isUsed());
    }

    @Test
    @DisplayName("요청자의 userId와 구매자의 userId가 불일치하여 구매확정에 실패한다.")
    void buy_Confirmation_Fail_Invalidate_Buy_Ownership() {
        Trade fakeTrade = Trade.builder().isUsed(false).buyerId(buyerId + 1).build();
        Mockito.when(tradeMapper.findById(tradeId)).thenReturn(Optional.of(fakeTrade));

        Assertions.assertThrows(NotMatchBuyOwnership.class,
                () -> tradeService.buyConfirmation(trade.getId(), buyerId));
    }
}