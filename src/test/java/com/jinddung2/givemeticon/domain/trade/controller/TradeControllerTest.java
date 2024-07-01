package com.jinddung2.givemeticon.domain.trade.controller;

import com.jinddung2.givemeticon.BasicControllerTest;
import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.controller.dto.ItemUsageConfirmationDto;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.exception.TradeErrorCode;
import com.jinddung2.givemeticon.domain.trade.facade.TradeReadeFacade;
import com.jinddung2.givemeticon.domain.trade.facade.TradeWriteFacade;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = TradeController.class)
@ContextConfiguration(classes = TradeController.class)
class TradeControllerTest extends BasicControllerTest {

    @MockBean
    TradeWriteFacade tradeWriteFacade;

    @MockBean
    TradeReadeFacade tradeReadeFacade;

    @Test
    @DisplayName("거래에 성공한다.")
    void create_Trade() throws Exception {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        mockHttpSession.setAttribute(LOGIN_USER, buyer.getId());

        when(tradeWriteFacade.transact(sale.getId(), buyer.getId())).thenReturn(trade.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/trades/sales/" + sale.getId())
                        .sessionAttr(LOGIN_USER, buyer.getId()))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(trade.getId()));

        verify(tradeWriteFacade, times(1)).transact(sale.getId(), buyer.getId());
    }

    @Test
    @DisplayName("이미 거래된 상품이라 실패한다.")
    void create_Trade_Fail_Already_Bought() throws Exception {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);

        doThrow(new AlreadyBoughtSaleException()).when(tradeWriteFacade).transact(sale.getId(), buyer.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/trades/sales/" + sale.getId())
                        .sessionAttr(LOGIN_USER, buyer.getId()))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(TradeErrorCode.ALREADY_BOUGHT_SALE.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(TradeErrorCode.ALREADY_BOUGHT_SALE.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(TradeErrorCode.ALREADY_BOUGHT_SALE.getErrorDetail()));
    }

    @Test
    @DisplayName("구매 상세페이지 가져오는데 성공한다.")
    void get_Trade_Detail() throws Exception {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        TradeDto result = TradeDto.of(trade, sale, item);

        when(tradeReadeFacade.getTradeDetail(trade.getId(), buyer.getId())).thenReturn(result);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/trades/" + trade.getId())
                        .sessionAttr(LOGIN_USER, buyer.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(result.getId()))
                .andExpect(jsonPath("$.data.isUsed").value(result.isUsed()))
                .andExpect(jsonPath("$.data.expiredDate").value(result.getExpiredDate().toString()))
                .andExpect(jsonPath("$.data.restDay").value(result.getRestDay()))
                .andExpect(jsonPath("$.data.boughtDate").value(result.getBoughtDate().toString()))
                .andExpect(jsonPath("$.data.tradePrice").value(result.getTradePrice()))
                .andExpect(jsonPath("$.data.itemPrice").value(result.getItemPrice()))
                .andExpect(jsonPath("$.data.discountRate").value(result.getDiscountRate()));

        verify(tradeReadeFacade).getTradeDetail(trade.getId(), buyer.getId());
    }

    @Test
    @DisplayName("사용 상세페이지 가져오는데 성공한다.")
    void get_TradeFor_Confirm_Usage() throws Exception {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        Brand brand = BrandFixture.createBrandFixture();
        ItemUsageConfirmationDto result = ItemUsageConfirmationDto.of(trade, sale, item, BrandDto.of(brand));

        when(tradeReadeFacade.getTradeForConfirmUsage(trade.getId(), buyer.getId())).thenReturn(result);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/trades/" + trade.getId() + "/confirm-usage")
                        .sessionAttr(LOGIN_USER, buyer.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.brandName").value(result.getBrandName()))
                .andExpect(jsonPath("$.data.itemName").value(result.getItemName()))
                .andExpect(jsonPath("$.data.expiredDate").value(result.getExpiredDate().toString()))
                .andExpect(jsonPath("$.data.barcodeNum").value(result.getBarcodeNum()))
                .andExpect(jsonPath("$.data.isUsed").value(result.isUsed()));

        verify(tradeReadeFacade).getTradeForConfirmUsage(trade.getId(), buyer.getId());
    }

    @Test
    @DisplayName("구매한 상품 중 미사용된 상품들 가져오는데 성공한다.")
    void get_Unused_Trade_History() throws Exception {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade1 = TradeFixture.createTradeFixture(10, buyer, sale, item);
        Trade trade2 = TradeFixture.createTradeFixture(20, buyer, sale, item);
        Trade trade3 = TradeFixture.createTradeFixture(30, buyer, sale, item);
        List<TradeDto> responseBody = new ArrayList<>();
        responseBody.add(TradeDto.of(trade1, sale, item));
        responseBody.add(TradeDto.of(trade2, sale, item));
        responseBody.add(TradeDto.of(trade3, sale, item));

        when(tradeReadeFacade.getUnusedTradeHistory(buyer.getId(), false, false, 0))
                .thenReturn(responseBody);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/trades/my")
                        .sessionAttr(LOGIN_USER, buyer.getId())
                        .param("orderByBoughtDate", "false")
                        .param("orderByExpiredDate", "false")
                        .param("page", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(responseBody.size()))
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].isUsed").value(false))
                .andExpect(jsonPath("$.data[1].id").exists())
                .andExpect(jsonPath("$.data[1].isUsed").value(false))
                .andExpect(jsonPath("$.data[2].id").exists())
                .andExpect(jsonPath("$.data[2].isUsed").value(false));

        verify(tradeReadeFacade).getUnusedTradeHistory(buyer.getId(), false, false, 0);
    }

    @Test
    @DisplayName("구매 확정에 성공한다.")
    void buy_Confirmation() throws Exception {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        String result = "Successfully buy confirmation";

        doNothing().when(tradeWriteFacade).buyConfirmation(trade.getId(), buyer.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/trades/" + trade.getId())
                        .sessionAttr(LOGIN_USER, buyer.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(result));

        verify(tradeWriteFacade).buyConfirmation(trade.getId(), buyer.getId());
    }
}