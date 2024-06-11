package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.sale.validator.SaleCreateValidator;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.SaleFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaleWriteFacadeTest {

    @InjectMocks
    SaleWriteFacade sut;

    @Mock
    ItemService itemService;

    @Mock
    UserService userService;

    @Mock
    SaleService saleService;

    @Mock
    SaleCreateValidator saleCreateValidator;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("판매 아이템 생성에 성공한다.")
    void create_Sale_Success() {
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(seller, item);

        when(itemService.isExists(item.getId())).thenReturn(true);
        when(userService.getUserInfo(seller.getId())).thenReturn(UserDto.of(seller));
        when(saleService.save(item.getId(), seller.getId(), request)).thenReturn(sale.getId());

        int result = sut.createSale(item.getId(), seller.getId(), request);

        verify(saleCreateValidator).validate(request);
        verify(saleService).save(item.getId(), seller.getId(), request);
        assertThat(result).isEqualTo(sale.getId());
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 판매할 아이템 생성에 실패한다.")
    void create_Sale_Fail_Not_Found_Item() {
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        User seller = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();

        when(itemService.isExists(item.getId())).thenReturn(false);

        Assertions.assertThrows(NotFoundItemException.class,
                () -> sut.createSale(item.getId(), seller.getId(), request));
    }

    @Test
    @DisplayName("계좌 등록이 되어 있지 않아서 판매할 아이템 생성에 실패한다.")
    void create_Sale_Fail_NOT_REGISTER_ACCOUNT() {
        SaleCreateRequest request = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        User seller = UserFixture.createOnlyUserFixture(now);
        Item item = ItemFixture.createItemFixture();

        when(itemService.isExists(item.getId())).thenReturn(true);
        when(userService.getUserInfo(seller.getId())).thenReturn(UserDto.of(seller));

        Assertions.assertThrows(NotRegistrSellerException.class,
                () -> sut.createSale(item.getId(), seller.getId(), request));
    }
}