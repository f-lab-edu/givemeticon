package com.jinddung2.givemeticon.brand.application;

import com.jinddung2.givemeticon.brand.domain.Brand;
import com.jinddung2.givemeticon.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.brand.exception.NotFoundBrandException;
import com.jinddung2.givemeticon.brand.infrastructure.mapper.BrandMapper;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandMapper brandMapper;

    public int save(BrandCreateRequest request) {
        if (isNameExists(request.name())) {
            throw new DuplicatedBrandNameException();
        }

        Brand brand = request.toEntity();
        return brandMapper.save(brand);
    }

    public String updateName(int id, String newName) {
        Brand brand = validateBrand(id);
        brand.updateName(newName);
        brandMapper.updateName(id, newName);
        return newName;
    }

    private Brand validateBrand(int id) {
        return brandMapper.findById(id).orElseThrow(NotFoundBrandException::new);
    }

    private boolean isNameExists(String name) {
        return brandMapper.existsByName(name);
    }
}
