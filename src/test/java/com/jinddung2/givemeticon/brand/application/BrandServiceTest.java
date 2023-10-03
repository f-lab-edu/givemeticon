package com.jinddung2.givemeticon.brand.application;

import com.jinddung2.givemeticon.brand.application.dto.BrandDto;
import com.jinddung2.givemeticon.brand.domain.Brand;
import com.jinddung2.givemeticon.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.brand.exception.NotFoundBrandException;
import com.jinddung2.givemeticon.brand.infrastructure.mapper.BrandMapper;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import com.jinddung2.givemeticon.brand.presentation.request.BrandUpdateNameRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
class BrandServiceTest {

    @InjectMocks
    BrandService brandService;

    @Mock
    BrandMapper brandMapper;

    Brand brand;
    BrandCreateRequest brandCreateRequest;
    BrandUpdateNameRequest brandUpdateNameRequest;

    @BeforeEach
    void setUp() {
        brand = Brand.builder()
                .id(100)
                .categoryId(101)
                .name("testBrand")
                .build();
        brandCreateRequest = new BrandCreateRequest(100, "testBrand");
        brandUpdateNameRequest = new BrandUpdateNameRequest("updateNameTest");
    }

    @Test
    @DisplayName("브랜드 저장하는데 성공한다.")
    void save_Success() {
        Mockito.when(brandMapper.existsByName(brandCreateRequest.name())).thenReturn(false);

        brandService.save(brandCreateRequest);

        Mockito.verify(brandMapper).save(any(Brand.class));
    }

    @Test
    @DisplayName("브랜드 단건조회에 성공한다.")
    void get_Brand_Success() {
        Mockito.when(brandMapper.findById(brand.getId())).thenReturn(Optional.of(brand));

        BrandDto brandDto = brandService.getBrand(brand.getId());

        assertEquals(brand.getId(), brandDto.getId());
        assertEquals(brand.getCategoryId(), brandDto.getCategoryId());
        assertEquals(brand.getName(), brandDto.getName());
    }

    @Test
    @DisplayName("브랜드가 없어 단건조회에 실패한다.")
    void get_Brand_Fail_Not_Found_Brand() {
        Mockito.when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundBrandException.class, () -> brandService.getBrand(brand.getId()));
    }

    @Test
    @DisplayName("이미 존재하는 브랜드명이라 저장하는데 실패한다.")
    void save_Fail_Exist_Brand_Name() {
        Mockito.when(brandMapper.existsByName(brandCreateRequest.name())).thenReturn(true);

        assertThrows(DuplicatedBrandNameException.class, () -> brandService.save(brandCreateRequest));
    }

    @Test
    @DisplayName("브랜드명을 바꾸는데 성공한다.")
    void update_Name_Success() {
        Mockito.when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.of(brand));
        doNothing().when(brandMapper).updateName(brand.getId(), brandUpdateNameRequest.name());

        brandService.updateName(brand.getId(), brandUpdateNameRequest.name());

        assertEquals(brand.getName(), brandUpdateNameRequest.name());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드명을 바꾸는데 실패한다.")
    void update_Name_Fail_Not_Found_Brand() {
        Mockito.when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundBrandException.class, () -> {
            brandService.updateName(brand.getId(), brandUpdateNameRequest.name());
        });
    }

    @Test
    @DisplayName("브랜드를 삭제에 성공한다")
    void delete_Brand_Success() {
        Mockito.when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.of(brand));

        brandService.delete(brand.getId());

        Mockito.verify(brandMapper).deleteById(brand.getId());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드 제거에 실패한다.")
    void delete_Brand_Fail_Not_Found_Brand() {
        Mockito.when(brandMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundBrandException.class, () -> {
            brandService.delete(brand.getId());
        });
    }
}