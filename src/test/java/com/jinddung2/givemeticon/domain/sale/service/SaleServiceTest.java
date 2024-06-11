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
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.SaleFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jinddung2.givemeticon.common.utils.PaginationUtil.makePagingParamMap;
import static com.jinddung2.givemeticon.common.utils.constants.PageSize.SALE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SaleServiceTest {

    @InjectMocks
    SaleService sut;
    @Mock
    SaleMapper saleMapper;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("판매할 아이템 생성에 성공한다.")
    void save_Success() {
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        Sale fakeSale = request.toEntity();
        int itemId = 10, sellerId = 20;
        fakeSale.updateItemId(itemId);
        fakeSale.updateSellerId(sellerId);
        Mockito.when(saleMapper.existsByBarcode(fakeSale.getBarcode())).thenReturn(false);
        Mockito.when(saleMapper.save(any(Sale.class))).thenReturn(10);

        int result = sut.save(itemId, sellerId, request);

        Mockito.verify(saleMapper).save(fakeSale);
        assertThat(result).isEqualTo(fakeSale.getId());
    }

    @Test
    @DisplayName("바코드가 중복이라 예외를 던진다")
    void validate_Fail_Duplicate_Barcode() {
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        Mockito.when(saleMapper.existsByBarcode(request.barcode()))
                .thenReturn(true);

        Assertions.assertThrows(DuplicatedBarcodeException.class,
                () -> sut.validateDuplicateBarcode(request.barcode()));
    }

    @Test
    @DisplayName("판매 상품 단건 조회에 성공한다.")
    void get_Sale_Success() {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(seller, item);
        Mockito.when(saleMapper.findById(sale.getId())).thenReturn(Optional.of(sale));
        SaleDto expected = SaleDto.of(sale);

        SaleDto result = sut.getAvailableSaleForItem(sale.getId());

        Mockito.verify(saleMapper).findById(sale.getId());
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("판매 상품이 존재하지 않아 단건 조회에 실패한다.")
    void get_Sale_Fail_Not_Found_Sale() {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(seller, item);
        Mockito.when(saleMapper.findById(sale.getId())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundSaleException.class,
                () -> sut.getSale(sale.getId()));
    }

    @Test
    @DisplayName("판매 상품이 유효기긴이 지났기에 단건 조회에 실패한다.")
    void get_Sale_Fail_Expired() {
        Sale fakeSale = Sale.builder().id(20).expirationDate(LocalDate.now().minusDays(1)).build();
        Mockito.when(saleMapper.findById(fakeSale.getId())).thenReturn(Optional.of(fakeSale));

        Assertions.assertThrows(ExpiredSaleException.class,
                () -> sut.getAvailableSaleForItem(fakeSale.getId()));
    }

    @Test
    @DisplayName("판매 상품이 이미 거래 되어 단건 조회에 실패한다.")
    void get_Sale_Fail_Already_Bought() {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createBoughtSaleFixture(seller, item);
        Mockito.when(saleMapper.findById(sale.getId())).thenReturn(Optional.of(sale));

        Assertions.assertThrows(AlreadyBoughtSaleException.class,
                () -> sut.getAvailableSaleForItem(sale.getId()));
    }

    @Test
    @DisplayName("판매 상품 재고조회에 성공한다.")
    void get_Sales_By_ItemId_Success() {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale1 = SaleFixture.createSaleFixture(10, seller, item);
        Sale sale2 = SaleFixture.createSaleFixture(20, seller, item);
        Sale sale3 = SaleFixture.createSaleFixture(30, seller, item);
        List<Sale> sales = List.of(sale1, sale2, sale3);
        List<SaleDto> expected = sales.stream().map(SaleDto::of).toList();

        Mockito.when(saleMapper.findNotBoughtSalesByItemId(item.getId())).thenReturn(sales);

        List<SaleDto> result = sut.getAvailableSalesForItem(item);

        assertThat(result.size()).isEqualTo(expected.size());
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i)).isEqualTo(expected.get(i));
        }
    }

    @Test
    @DisplayName("내 판매 목록을 가져오는데 성공한다.")
    void get_My_Sales() {
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale1 = SaleFixture.createSaleFixture(10, seller, item);
        Sale sale2 = SaleFixture.createSaleFixture(20, seller, item);
        Sale sale3 = SaleFixture.createSaleFixture(30, seller, item);
        List<Sale> sales = List.of(sale1, sale2, sale3);
        Mockito.when(saleMapper.findMySalesBySellerId(seller.getId())).thenReturn(sales);

        List<Sale> result = sut.getMyTradedSales(seller.getId());

        assertThat(result.size()).isEqualTo(sales.size());
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i)).isEqualTo(sales.get(i));
        }
    }

    @Test
    @DisplayName("페이징 처리한 판매 목록을 가져올 때, 예상한 크기와 같은지 검증한다.")
    void get_My_Sales_With_page() {
        int page = 1;
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale1 = SaleFixture.createSaleFixture(10, seller, item);
        Sale sale2 = SaleFixture.createSaleFixture(20, seller, item);
        Sale sale3 = SaleFixture.createSaleFixture(30, seller, item);
        Sale sale4 = SaleFixture.createSaleFixture(40, seller, item);
        Sale sale5 = SaleFixture.createSaleFixture(50, seller, item);
        Sale sale6 = SaleFixture.createSaleFixture(60, seller, item);
        Sale sale7 = SaleFixture.createSaleFixture(70, seller, item);
        Sale sale8 = SaleFixture.createSaleFixture(80, seller, item);
        List<Sale> sales = List.of(sale1, sale2, sale3, sale4, sale5, sale6, sale7, sale8);
        Map<String, Object> pageInfo = makePagingParamMap(seller.getId(), page, SALE.getSize());
        Mockito.when(saleMapper.findMyTradedSales(pageInfo)).thenReturn(sales.subList(0, SALE.getSize()));

        List<Sale> result = sut.getMyTradedSales(seller.getId(), page);

        assertThat(result.size()).isEqualTo(SALE.getSize());
    }
}