package com.jinddung2.givemeticon.brand.application;

import com.jinddung2.givemeticon.brand.domain.Brand;
import com.jinddung2.givemeticon.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.brand.infrastructure.mapper.BrandMapper;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class BrandServiceTest {

    @InjectMocks
    BrandService brandService;

    @Mock
    BrandMapper brandMapper;

    BrandCreateRequest brandCreateRequest;

    @BeforeEach
    void setUp() {
        brandCreateRequest = new BrandCreateRequest(100, "testBrand");
    }

    @Test
    @DisplayName("브랜드 저장하는데 성공한다.")
    void save_Success() {
        Mockito.when(brandMapper.existsByName(brandCreateRequest.name())).thenReturn(false);

        brandService.save(brandCreateRequest);

        Mockito.verify(brandMapper).save(any(Brand.class));
    }

    @Test
    @DisplayName("이미 존재하는 브랜드명이라 저장하는데 실패한다.")
    void save_Fail_Exist_Brand_Name() {
        Mockito.when(brandMapper.existsByName(brandCreateRequest.name())).thenReturn(true);

        assertThrows(DuplicatedBrandNameException.class, () -> brandService.save(brandCreateRequest));
    }
}