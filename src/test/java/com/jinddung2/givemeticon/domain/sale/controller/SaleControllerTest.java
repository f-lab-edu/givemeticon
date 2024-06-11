package com.jinddung2.givemeticon.domain.sale.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.BasicControllerTest;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.ItemErrorCode;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.exception.*;
import com.jinddung2.givemeticon.domain.sale.facade.SaleReadFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleWriteFacade;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.SaleFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = SaleController.class)
@ContextConfiguration(classes = SaleController.class)
class SaleControllerTest extends BasicControllerTest {

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    SaleWriteFacade saleWriteFacade;

    @MockBean
    SaleService saleService;

    @MockBean
    SaleReadFacade saleReadFacade;

    @Test
    @DisplayName("판매 상품 생성에 성공한다.")
    void create_Sale_Success() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(seller, item);
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        when(saleWriteFacade.createSale(item.getId(), seller.getId(), request)).thenReturn(sale.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/sales/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(sale.getId()));

        verify(saleWriteFacade).createSale(item.getId(), seller.getId(), request);
    }

    @Test
    @DisplayName("전시 상품이 존재하지 않아 판매 상품 생성에 실패한다.")
    void create_Sale_Fail_NOT_FOUND_ITEM() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        doThrow(new NotFoundItemException()).when(saleWriteFacade)
                .createSale(item.getId(), seller.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/sales/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(ItemErrorCode.NOT_FOUND_ITEM.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(ItemErrorCode.NOT_FOUND_ITEM.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(ItemErrorCode.NOT_FOUND_ITEM.getErrorDetail()));
    }

    @Test
    @DisplayName("판매자로 등록되어 있지 않아 판매 상품 생성에 실패한다.")
    void create_Sale_FAIL_NOT_REGISTER_ACCOUNT() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        doThrow(new NotRegistrSellerException()).when(saleWriteFacade)
                .createSale(item.getId(), seller.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/sales/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(SaleErrorCode.NOT_REGISTER_ACCOUNT.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(SaleErrorCode.NOT_REGISTER_ACCOUNT.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(SaleErrorCode.NOT_REGISTER_ACCOUNT.getErrorDetail()));
    }

    @Test
    @DisplayName("유효기간이 만료 되어 판매 상품 생성에 실패한다.")
    void create_Sale_Fail_Expired_Date() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        doThrow(new ExpiredSaleException()).when(saleWriteFacade)
                .createSale(item.getId(), seller.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/sales/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(SaleErrorCode.SALE_EXPIRED_DATE.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(SaleErrorCode.SALE_EXPIRED_DATE.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(SaleErrorCode.SALE_EXPIRED_DATE.getErrorDetail()));
    }

    @Test
    @DisplayName("바코드 번호가 이미 등록되어 판매 상품 생성에 실패한다.")
    void create_Sale_Fail_Duplicated_Barcode_Number() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        doThrow(new DuplicatedBarcodeException()).when(saleWriteFacade)
                .createSale(item.getId(), seller.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/sales/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(SaleErrorCode.DUPLICATED_BARCODE_NUMBER.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(SaleErrorCode.DUPLICATED_BARCODE_NUMBER.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(SaleErrorCode.DUPLICATED_BARCODE_NUMBER.getErrorDetail()));
    }

    @Test
    @DisplayName("판매 상품 단건 조회에 성공한다.")
    void get_Sale_Success() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(seller, item);
        SaleDto result = SaleDto.of(sale);

        when(saleReadFacade.getAvailableSales(sale.getId())).thenReturn(result);
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/sales/" + sale.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .sessionAttr(LOGIN_USER, seller.getId()))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(result.getId()))
                .andExpect(jsonPath("$.data.itemId").value(result.getItemId()))
                .andExpect(jsonPath("$.data.sellerId").value(result.getSellerId()))
                .andExpect(jsonPath("$.data.barcode").value(result.getBarcode()))
                .andExpect(jsonPath("$.data.expirationDate").value(result.getExpirationDate().toString()))
                .andExpect(jsonPath("$.data.isBought").value(result.isBought()))
                .andExpect(jsonPath("$.data.isBoughtDate").value(result.getIsBoughtDate().toString()))
                .andExpect(jsonPath("$.data.createdDate").value(result.getCreatedDate()));

        verify(saleReadFacade).getAvailableSales(sale.getId());
    }

    @Test
    @DisplayName("판매 상품이 존재하지 않아 단건 조회에 실패한다.")
    void get_Sale_Fail_Not_Found_Sale() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(seller, item);

        doThrow(new NotFoundSaleException())
                .when(saleReadFacade).getAvailableSales(sale.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/sales/" + sale.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(SaleErrorCode.NOT_FOUND_SALE.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(SaleErrorCode.NOT_FOUND_SALE.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(SaleErrorCode.NOT_FOUND_SALE.getErrorDetail()));

    }

    @Test
    @DisplayName("전시 아이템에 해당하는 판매 상품들 조회에 성공한다.")
    void get_Sales_By_ItemId_Success() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();

        Sale sale1 = SaleFixture.createSaleFixture(10, seller, item);
        Sale sale2 = SaleFixture.createSaleFixture(10, seller, item);
        Sale sale3 = SaleFixture.createSaleFixture(10, seller, item);
        List<SaleDto> responseBody = new ArrayList<>();
        responseBody.add(SaleDto.of(sale1));
        responseBody.add(SaleDto.of(sale2));
        responseBody.add(SaleDto.of(sale3));

        when(saleReadFacade.getSalesForItem(item.getId())).thenReturn(responseBody);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/sales/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(responseBody.size()))
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].barcode").exists())
                .andExpect(jsonPath("$.data[0].isBought").value(false))
                .andExpect(jsonPath("$.data[1].id").exists())
                .andExpect(jsonPath("$.data[1].barcode").exists())
                .andExpect(jsonPath("$.data[1].isBought").value(false))
                .andExpect(jsonPath("$.data[2].id").exists())
                .andExpect(jsonPath("$.data[2].barcode").exists())
                .andExpect(jsonPath("$.data[2].isBought").value(false));

        verify(saleReadFacade).getSalesForItem(item.getId());
    }

    @Test
    @DisplayName("전시 상품이 존재하지 않아 판매 상품들 조회에 실패한다.")
    void get_Sales_By_ItemId_Fail_Not_Found_ItemId() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();

        doThrow(new NotFoundItemException())
                .when(saleReadFacade).getSalesForItem(item.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/sales/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(ItemErrorCode.NOT_FOUND_ITEM.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(ItemErrorCode.NOT_FOUND_ITEM.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(ItemErrorCode.NOT_FOUND_ITEM.getErrorDetail()));
    }

    @Test
    @DisplayName("내 판매금액 조회에 성공한다.")
    void get_Total_Amount_For_Sales() throws Exception {
        User seller = UserFixture.createUserFixture(now);
        BigDecimal result = BigDecimal.valueOf(10000);

        when(saleReadFacade.getTotalAmountForSales(seller.getId()))
                .thenReturn(result);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/sales/my/total-amount")
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(result));

        verify(saleReadFacade).getTotalAmountForSales(seller.getId());
    }

    @Test
    @DisplayName("내 판매목록 중 거래된 상품들을 조회한다.")
    void get_Confirmed_Sales_() throws Exception {
        int page = 0;
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale1 = SaleFixture.createBoughtSaleFixture(seller, item);
        Sale sale2 = SaleFixture.createBoughtSaleFixture(seller, item);
        Sale sale3 = SaleFixture.createBoughtSaleFixture(seller, item);
        List<MySaleDto> responseBody = new ArrayList<>();
        responseBody.add(MySaleDto.of(item, sale1, BigDecimal.valueOf(1000)));
        responseBody.add(MySaleDto.of(item, sale2, BigDecimal.valueOf(2000)));
        responseBody.add(MySaleDto.of(item, sale3, BigDecimal.valueOf(3000)));

        when(saleReadFacade.getTradedAndConfirmedSales(seller.getId(), page)).thenReturn(responseBody);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/sales/my")
                        .sessionAttr(LOGIN_USER, seller.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(responseBody.size()))
                .andExpect(jsonPath("$.data[0].isBoughtDate").exists())
                .andExpect(jsonPath("$.data[1].isBoughtDate").exists())
                .andExpect(jsonPath("$.data[2].isBoughtDate").exists());

        verify(saleReadFacade).getTradedAndConfirmedSales(seller.getId(), page);
    }
}