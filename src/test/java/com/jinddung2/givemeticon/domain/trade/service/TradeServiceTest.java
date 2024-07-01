package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.NotFoundTradeException;
import com.jinddung2.givemeticon.domain.trade.mapper.TradeMapper;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.SaleFixture;
import com.jinddung2.givemeticon.fixture.TradeFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jinddung2.givemeticon.common.utils.PaginationUtil.makePagingParamMap;
import static com.jinddung2.givemeticon.common.utils.constants.PageSize.TRADE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @InjectMocks
    TradeService sut;

    @Mock
    TradeMapper tradeMapper;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("상품 기간이 1주일 초과하면 기존 상품의 10% 할인된 가격으로 거래 상품이 등록된다..")
    void save_10PER_Discount_Trade() {
        double discountRate = 0.10;
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        BigDecimal originalPrice = BigDecimal.valueOf(item.getPrice());
        BigDecimal discountPrice = originalPrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal expected = originalPrice.subtract(discountPrice);
        sut.save(sale, item, buyer.getId());

        assertThat(trade.getTradePrice()).isEqualTo(expected);
    }

    @Test
    @DisplayName("상품 기간이 1주일 이하라면 기존 상품의 15% 할인된 가격으로 거래 상품이 등록된다.")
    void save_15PER_Discount_Trade() {
        double discountRate = 0.15;
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixtureWithin7Days(buyer, sale, item);

        BigDecimal originalPrice = BigDecimal.valueOf(item.getPrice());
        BigDecimal discountPrice = originalPrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal expected = originalPrice.subtract(discountPrice);

        sut.save(sale, item, buyer.getId());

        assertThat(trade.getTradePrice()).isEqualTo(expected);
    }

    @Test
    @DisplayName("거래 단건조회에 성공한다.")
    void get_Trade_By_Id() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        Mockito.when(tradeMapper.findById(trade.getId())).thenReturn(Optional.of(trade));

        Trade result = sut.getTrade(trade.getId());

        assertThat(trade.getId()).isEqualTo(result.getId());
        assertThat(trade.getTradePrice()).isEqualTo(result.getTradePrice());
    }

    @Test
    @DisplayName("판매자 id가 일치하는 거래 단건조회에 성공한다.")
    void get_Trade_By_SaleId() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        Mockito.when(tradeMapper.findBySaleId(trade.getSaleId())).thenReturn(Optional.of(trade));

        Optional<Trade> result = sut.getTradeBySaleId(trade.getSaleId());

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get().isUsed()).isFalse();
    }

    @Test
    @DisplayName("판매자 id가 일치하는 거래 단건조회 했는데 데이터가 없다.")
    void get_Trade_By_SaleId_Not_Found_Trade() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        Mockito.when(tradeMapper.findBySaleId(trade.getSaleId())).thenReturn(Optional.empty());

        Optional<Trade> result = sut.getTradeBySaleId(trade.getSaleId());

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("거래번호가 없어 단건조회에 실패한다.")
    void get_Trade_ById_Fail_Not_Found_Trade() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        Mockito.when(tradeMapper.findById(trade.getId())).thenReturn(Optional.empty());

        assertThrows(NotFoundTradeException.class,
                () -> sut.getTrade(trade.getId()));
    }

    @Test
    @DisplayName("미사용된 아이템을 조회한다.")
    void getMyUnusedItemHistory() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        int page = 0;
        Map<String, Object> pageInfo = makePagingParamMap(buyer.getId(), page, TRADE.getSize());
        boolean orderByBoughtDate = false;
        boolean orderByExpiredDate = false;


        List<Trade> tradeList = new ArrayList<>();
        tradeList.add(trade);
        Mockito.when(tradeMapper.findMyBoughtGifticon(pageInfo, orderByBoughtDate, orderByExpiredDate)).thenReturn(tradeList);

        List<Trade> result = sut.getMyUnusedItemHistory(buyer.getId(), orderByBoughtDate, orderByExpiredDate, page);

        assertThat(result.size()).isEqualTo(tradeList.size());
    }

    @Test
    @DisplayName("구매 확정에 성공한다.")
    void buy_Confirmation_Success() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        Mockito.when(tradeMapper.findById(trade.getId())).thenReturn(Optional.of(trade));

        sut.buyConfirmation(trade.getId(), buyer.getId());

        assertThat(trade.isUsed()).isTrue();
    }

    @Test
    @DisplayName("이미 구매 확정된 상태라서 구매 확정을 취소한다.")
    void buy_Confirmation_Fail_Already_Buy_Confirmation() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        Trade fakeTrade = Trade.builder().isUsed(true).buyerId(buyer.getId()).build();
        Mockito.when(tradeMapper.findById(trade.getId())).thenReturn(Optional.of(fakeTrade));

        sut.buyConfirmation(trade.getId(), buyer.getId());

        assertThat(trade.isUsed()).isFalse();
    }
}