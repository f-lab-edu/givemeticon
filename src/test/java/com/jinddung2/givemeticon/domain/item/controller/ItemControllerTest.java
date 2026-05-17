package com.jinddung2.givemeticon.domain.item.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.BasicControllerTest;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.controller.dto.PopularItemDto;
import com.jinddung2.givemeticon.domain.item.controller.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.ItemErrorCode;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.facade.ItemWriteFacade;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.fixture.BrandFixture;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ItemController.class)
@ContextConfiguration(classes = ItemController.class)
class ItemControllerTest extends BasicControllerTest {

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ItemWriteFacade itemWriteFacade;

    @MockBean
    ItemService itemService;


    @Test
    @DisplayName("해당 브랜드의 전시용 아이템 생성에 성공한다.")
    void createItem_Success() throws Exception {
        ItemCreateRequest request = new ItemCreateRequest("testName", 10000);
        Brand brand = BrandFixture.createBrandFixture();
        Item item = ItemFixture.createItemFixture();
        ItemDto response = ItemDto.of(item);

        when(itemWriteFacade.createItem(brand.getId(), request)).thenReturn(response);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/items/brand/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(response.getId()))
                .andExpect(jsonPath("$.data.brandId").value(response.getBrandId()))
                .andExpect(jsonPath("$.data.name").value(response.getName()))
                .andExpect(jsonPath("$.data.price").value(response.getPrice()));

        Mockito.verify(itemWriteFacade).createItem(brand.getId(), request);
    }

    @Test
    @DisplayName("전시용 아이템 단건 조회에 성공한다.")
    void getItem_Success() throws Exception {
        int userId = 1;
        Item item = ItemFixture.createItemFixture();
        ItemDto response = ItemDto.of(item);

        when(itemService.getItemAndIncreaseViewCount(item.getId(), userId)).thenReturn(response);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/items/" + item.getId())
                        .sessionAttr(LOGIN_USER, userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(response.getId()))
                .andExpect(jsonPath("$.data.brandId").value(response.getBrandId()))
                .andExpect(jsonPath("$.data.name").value(response.getName()))
                .andExpect(jsonPath("$.data.price").value(response.getPrice()));

        Mockito.verify(itemService).getItemAndIncreaseViewCount(item.getId(), userId);
    }

    @Test
    @DisplayName("인기 아이템 목록 조회에 성공한다.")
    void getPopularItems_Success() throws Exception {
        PopularItemDto response = new PopularItemDto();
        response.setId(1);
        response.setBrandId(2);
        response.setName("popular item");
        response.setPrice(1000);
        response.setLikeCount(10);
        response.setViewCount(20);

        when(itemService.getPopularItems("LIKE", 50)).thenReturn(List.of(response));

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/items/popular")
                        .param("sort", "LIKE")
                        .param("limit", "50")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(response.getId()))
                .andExpect(jsonPath("$.data[0].brandId").value(response.getBrandId()))
                .andExpect(jsonPath("$.data[0].name").value(response.getName()))
                .andExpect(jsonPath("$.data[0].price").value(response.getPrice()))
                .andExpect(jsonPath("$.data[0].likeCount").value(response.getLikeCount()))
                .andExpect(jsonPath("$.data[0].viewCount").value(response.getViewCount()));

        Mockito.verify(itemService).getPopularItems("LIKE", 50);
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 단건 조회에 실패한다.")
    void getItem_Fail_Not_Found_Item() throws Exception {
        int userId = 1;
        Brand brand = BrandFixture.createBrandFixture();
        doThrow(new NotFoundItemException()).when(itemService).getItemAndIncreaseViewCount(brand.getId(), userId);
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/items/" + brand.getId())
                        .sessionAttr(LOGIN_USER, userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(ItemErrorCode.NOT_FOUND_ITEM.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(ItemErrorCode.NOT_FOUND_ITEM.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(ItemErrorCode.NOT_FOUND_ITEM.getErrorDetail()));
    }
}
