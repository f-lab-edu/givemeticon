package com.jinddung2.givemeticon.domain.item.facade;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.controller.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.fixture.BrandFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemWriteFacadeTest {

    @InjectMocks
    ItemWriteFacade sut;

    @Mock
    ItemService itemService;

    @Mock
    BrandService brandService;

    @Test
    @DisplayName("아이템 생성에 성공한다.")
    void create_Item_Success() {
        Brand brand = BrandFixture.createBrandFixture();
        ItemCreateRequest request = new ItemCreateRequest("testName", 10000);
        Item item = request.toEntity();
        when(brandService.getBrand(brand.getId())).thenReturn(BrandDto.of(brand));
        when(itemService.saveOrUpdate(any(Item.class))).thenReturn(item);

        ItemDto result = sut.createItem(brand.getId(), request);

        assertThat(result).isEqualTo(ItemDto.of(item));
    }
}
