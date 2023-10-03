package com.jinddung2.givemeticon.brand.application;

import com.jinddung2.givemeticon.brand.domain.Brand;
import com.jinddung2.givemeticon.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.brand.infrastructure.mapper.BrandMapper;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrandService {

    private final BrandMapper brandMapper;

    public int save(BrandCreateRequest request) {
        if (isNameExists(request.name())) {
            throw new DuplicatedBrandNameException();
        }

        Brand brand = request.toEntity();
        return brandMapper.save(brand);
    }

    private boolean isNameExists(String name) {
        return brandMapper.existsByName(name);
    }
}
