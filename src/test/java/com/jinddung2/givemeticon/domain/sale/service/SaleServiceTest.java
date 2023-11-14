package com.jinddung2.givemeticon.domain.sale.service;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.controller.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.sale.exception.ExpiredSaleException;
import com.jinddung2.givemeticon.domain.sale.exception.NotFoundSaleException;
import com.jinddung2.givemeticon.domain.sale.mapper.SaleMapper;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jinddung2.givemeticon.common.utils.PaginationUtil.makePagingParamMap;
import static com.jinddung2.givemeticon.common.utils.constants.PageSize.SALE;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SaleServiceTest {

    @InjectMocks
    SaleService saleService;
    @Mock
    SaleMapper saleMapper;

    SaleCreateRequest saleCreateRequest;
    int itemId, sellerId, saleId, page;
    Sale sale;
    Item item;

    @BeforeEach
    void setUp() {
        page = 0;
        saleCreateRequest = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        saleId = 10;
        sale = Sale.builder().id(saleId).expirationDate(LocalDate.of(2099, 12, 31)).build();
        itemId = 1;
        item = Item.builder().id(itemId).build();
        sellerId = 1;
    }

    @Test
    @DisplayName("판매할 아이템 생성에 성공한다.")
    void save_Success() {
        Sale fakeSale = saleCreateRequest.toEntity();
        fakeSale.updateItemId(itemId);
        fakeSale.updateSellerId(sellerId);

        Mockito.when(saleMapper.existsByBarcode(fakeSale.getBarcode())).thenReturn(false);
        Mockito.when(saleMapper.save(any(Sale.class))).thenReturn(10);

        int resultId = saleService.save(itemId, sellerId, saleCreateRequest);

        Mockito.verify(saleMapper).save(fakeSale);

        Assertions.assertEquals(fakeSale.getId(), resultId);
    }

    @Test
    @DisplayName("바코드가 중복이라 예외를 던진다")
    void validate_Fail_Duplicate_Barcode() {
        Mockito.when(saleMapper.existsByBarcode(saleCreateRequest.barcode()))
                .thenReturn(true);

        Assertions.assertThrows(DuplicatedBarcodeException.class,
                () -> saleService.validateDuplicateBarcode(saleCreateRequest.barcode()));
    }

    @Test
    @DisplayName("판매 상품 단건 조회에 성공한다.")
    void get_Sale_Success() {
        Mockito.when(saleMapper.findById(sale.getId())).thenReturn(Optional.of(sale));
        saleService.getAvailableSaleForItem(sale.getId());

        Mockito.verify(saleMapper).findById(saleId);
    }

    @Test
    @DisplayName("판매 상품이 존재하지 않아 단건 조회에 실패한다.")
    void get_Sale_Fail_Not_Found_Sale() {
        Mockito.when(saleMapper.findById(sale.getId())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundSaleException.class,
                () -> saleService.getSale(sale.getId()));
    }

    @Test
    @DisplayName("판매 상품이 유효기긴이 지났기에 단건 조회에 실패한다.")
    void get_Sale_Fail_Expired() {
        Sale fakeSale = Sale.builder().id(20).expirationDate(LocalDate.now().minusDays(1)).build();
        Mockito.when(saleMapper.findById(fakeSale.getId())).thenReturn(Optional.of(fakeSale));

        Assertions.assertThrows(ExpiredSaleException.class,
                () -> saleService.getAvailableSaleForItem(fakeSale.getId()));
    }

    @Test
    @DisplayName("판매 상품이 이미 구매되어 단건 조회에 실패한다.")
    void get_Sale_Fail_Already_Bought() {
        Sale fakeSale = Sale.builder()
                .id(20)
                .expirationDate(LocalDate.now().plusDays(1))
                .isBought(true)
                .isBoughtDate(new Date(20220101))
                .build();
        Mockito.when(saleMapper.findById(fakeSale.getId())).thenReturn(Optional.of(fakeSale));

        Assertions.assertThrows(AlreadyBoughtSaleException.class,
                () -> saleService.getAvailableSaleForItem(fakeSale.getId()));
    }

    @Test
    @DisplayName("상품 재고조회에 성공한다.")
    void get_Sales_By_ItemId_Success() {

        List<Sale> sales = List.of(
                Sale.builder().expirationDate(LocalDate.now().plusDays(1)).build(),
                Sale.builder().expirationDate(LocalDate.now().plusDays(1)).build(),
                Sale.builder().expirationDate(LocalDate.now().plusDays(1)).build());
        Mockito.when(saleMapper.findNotBoughtSalesByItemId(itemId)).thenReturn(sales);

        List<SaleDto> result = saleService.getAvailableSalesForItem(item);

        Assertions.assertEquals(3, result.size());
    }

    @Test
    @DisplayName("내 판매목록을 조회한다.")
    void get_My_Sales() {
        int userId = 1;
        List<Sale> sales = List.of(
                Sale.builder().sellerId(userId).expirationDate(LocalDate.now().plusDays(1)).build(),
                Sale.builder().sellerId(userId).expirationDate(LocalDate.now().plusDays(1)).build(),
                Sale.builder().sellerId(userId).expirationDate(LocalDate.now().plusDays(1)).build());
        Mockito.when(saleMapper.findMySalesBySellerId(userId)).thenReturn(sales);

        List<Sale> result = saleService.getMySales(userId);

        Assertions.assertEquals(sales.size(), result.size());
    }

    @Test
    @DisplayName("내 판매목록을 페이징 처리해서 조회한다.")
    void get_My_Sales_With_page() {
        int userId = 1;
        List<Sale> sales = List.of(
                Sale.builder().sellerId(userId).expirationDate(LocalDate.now().plusDays(1)).build(),
                Sale.builder().sellerId(userId).expirationDate(LocalDate.now().plusDays(1)).build(),
                Sale.builder().sellerId(userId).expirationDate(LocalDate.now().plusDays(1)).build());
        Map<String, Object> pageInfo = makePagingParamMap(userId, page, SALE.getSize());
        Mockito.when(saleMapper.findMySales(pageInfo)).thenReturn(sales);

        List<Sale> result = saleService.getMySales(userId, page);

        Assertions.assertEquals(sales.size(), result.size());
    }
}