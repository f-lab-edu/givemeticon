package com.jinddung2.givemeticon.domain.brand.service;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandCreateRequest;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandUpdateNameRequest;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.domain.brand.exception.EmptyBrandListException;
import com.jinddung2.givemeticon.domain.brand.exception.NotFoundBrandException;
import com.jinddung2.givemeticon.domain.brand.mapper.BrandMapper;
import com.jinddung2.givemeticon.fixture.BrandFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrandServiceTest {

    @InjectMocks
    BrandService suv;

    @Mock
    BrandMapper brandMapper;

    @Test
    @DisplayName("브랜드 저장하는데 성공한다.")
    void save_Success() {
        BrandCreateRequest request = new BrandCreateRequest(100, "testBrand");
        when(brandMapper.existsByName(request.name())).thenReturn(false);

        suv.save(request);

        verify(brandMapper).save(any(Brand.class));
    }

    @Test
    @DisplayName("브랜드 단건조회에 성공한다.")
    void get_Brand_Success() {
        Brand brand = BrandFixture.createBrandFixture();
        when(brandMapper.findById(brand.getId())).thenReturn(Optional.of(brand));

        BrandDto result = suv.getBrand(brand.getId());

        assertThat(brand.getId()).isEqualTo(result.getId());
        assertThat(brand.getCategoryId()).isEqualTo(result.getCategoryId());
        assertThat(brand.getName()).isEqualTo(result.getName());
    }

    @Test
    @DisplayName("브랜드가 없어 단건조회에 실패한다.")
    void get_Brand_Fail_Not_Found_Brand() {
        Brand brand = BrandFixture.createBrandFixture();
        when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundBrandException.class, () -> suv.getBrand(brand.getId()));
    }

    @Test
    @DisplayName("카테고리에 해당하는 브랜드들 조회에 성공한다.")
    void get_Brands_By_CategoryId() {
        int categoryId = 10;
        int page = 0;
        int pageSize = 10;
        Map<String, Object> params = Map.ofEntries(
                Map.entry("id", categoryId),
                Map.entry("pageSize", pageSize),
                Map.entry("offset", PageRequest.of(page, pageSize).getOffset()));
        List<Brand> brandList = Arrays.asList(
                Brand.builder().name("스타벅스").build(),
                Brand.builder().name("투썸플레이스").build(),
                Brand.builder().name("메머드").build());
        when(brandMapper.countBrandByCategoryId(categoryId)).thenReturn(pageSize);
        when(brandMapper.findAllByCategory(params)).thenReturn(brandList);

        List<BrandDto> brands = suv.getBrands(categoryId, page);

        assertEquals(brands.size(), brandList.size());
    }

    @Test
    @DisplayName("페이지에 해당하는 브랜드 데이터가 없어 브랜드 다건조회에 실패한다.")
    void get_Brands_By_CategoryId_Fail_No_Data() {
        int categoryId = 10;
        int page = 0;
        int pageSize = 10;
        Map<String, Object> params = Map.ofEntries(
                Map.entry("id", categoryId),
                Map.entry("pageSize", pageSize),
                Map.entry("offset", PageRequest.of(page, pageSize).getOffset()));

        when(brandMapper.countBrandByCategoryId(categoryId)).thenReturn(pageSize);
        when(brandMapper.findAllByCategory(params)).thenReturn(Collections.emptyList());

        assertThrows(EmptyBrandListException.class, () -> suv.getBrands(categoryId, page));

    }

    @Test
    @DisplayName("이미 존재하는 브랜드명이라 저장하는데 실패한다.")
    void save_Fail_Exist_Brand_Name() {
        BrandCreateRequest request = new BrandCreateRequest(100, "testBrand");
        when(brandMapper.existsByName(request.name())).thenReturn(true);

        assertThrows(DuplicatedBrandNameException.class, () -> suv.save(request));
    }

    @Test
    @DisplayName("브랜드명을 바꾸는데 성공한다.")
    void update_Name_Success() {
        BrandUpdateNameRequest request = new BrandUpdateNameRequest("updateNameTest");
        Brand brand = BrandFixture.createBrandFixture();
        when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.of(brand));
        doNothing().when(brandMapper).updateName(brand.getId(), request.name());

        suv.updateName(brand.getId(), request.name());

        assertEquals(brand.getName(), request.name());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드명을 바꾸는데 실패한다.")
    void update_Name_Fail_Not_Found_Brand() {
        BrandUpdateNameRequest request = new BrandUpdateNameRequest("updateNameTest");
        Brand brand = BrandFixture.createBrandFixture();
        when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundBrandException.class, () -> suv.updateName(brand.getId(), request.name()));
    }

    @Test
    @DisplayName("브랜드를 삭제에 성공한다")
    void delete_Brand_Success() {
        Brand brand = BrandFixture.createBrandFixture();
        when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.of(brand));

        suv.delete(brand.getId());

        verify(brandMapper).deleteById(brand.getId());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드 제거에 실패한다.")
    void delete_Brand_Fail_Not_Found_Brand() {
        Brand brand = BrandFixture.createBrandFixture();
        when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundBrandException.class, () -> suv.delete(brand.getId()));
    }
}