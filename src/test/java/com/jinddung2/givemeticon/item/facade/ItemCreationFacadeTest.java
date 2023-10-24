package com.jinddung2.givemeticon.item.facade;

import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.brand.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.facade.ItemCreationFacade;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class ItemCreationFacadeTest {

    @InjectMocks
    ItemCreationFacade itemCreationFacade;

    @Mock
    ItemService itemService;

    @Mock
    BrandService brandService;

    ItemCreateRequest itemCreateRequest;

    int brandId = 10;
    Item item;
    Brand brand;

    @BeforeEach
    void setUp() {
        itemCreateRequest = new ItemCreateRequest("testName", 10000);
        brand = Brand.builder()
                .id(brandId)
                .build();

        item = Item.builder()
                .name(itemCreateRequest.name())
                .price(itemCreateRequest.price())
                .build();
    }

    @Test
    @DisplayName("아이템 생성에 성공한다.")
    void create_Item_Success() {
        Mockito.when(brandService.getBrand(brandId)).thenReturn(new BrandDto());
        Mockito.when(itemService.save(any(Item.class))).thenReturn(item.getId());

        int itemId = itemCreationFacade.createItem(brandId, itemCreateRequest);

        Mockito.verify(brandService).getBrand(brandId);

        /**
         * Mock 객체의 메서드 호출을 검증할 때 실제 호출된 메서드의 인자와 예상되는 인자가 일치하지 않을 경우 발생할 수 있습니다.
         * Mock 객체는 객체 비교를 기본적으로 참조(Reference)를 기반으로 하기 때문에 객체 내용이 동일하더라도 참조가 다르면 일치하지 않는 것으로 간주됩니다.
         * argThat를 사용하면 객체 내용을 비교하고, 인자로 전달된 람다 표현식에서 객체 내용을 비교할 수 있습니다.
         * 따라서 객체 내용이 동일한 경우에도 인자로 전달된 람다 표현식이 일치하는지 확인할 수 있습니다.
         */
        Mockito.verify(itemService).save(argThat(item ->
                item.getName().equals(itemCreateRequest.name()) &&
                        item.getPrice() == itemCreateRequest.price() &&
                        item.getBrandId() == brandId
        ));

        assertEquals(item.getId(), itemId);
    }
}
