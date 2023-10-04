package com.jinddung2.givemeticon.brand.application;

import com.jinddung2.givemeticon.brand.application.dto.BrandDto;
import com.jinddung2.givemeticon.brand.domain.Brand;
import com.jinddung2.givemeticon.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.brand.exception.EmptyBrandListException;
import com.jinddung2.givemeticon.brand.exception.NotFoundBrandException;
import com.jinddung2.givemeticon.brand.infrastructure.mapper.BrandMapper;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jinddung2.givemeticon.common.utils.PaginationUtil.makePagingParamMap;

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

    public BrandDto getBrand(int id) {
        Brand brand = validateBrand(id);
        return toDto(brand);
    }

    public List<BrandDto> getBrands(int categoryId, int page) {
        Map<String, Object> paramMap = makePagingParamMap(categoryId, page, countBrandByCategoryId(categoryId));
        List<Brand> brands = brandMapper.findAllByCategory(paramMap);

        if (brands.isEmpty()) {
            throw new EmptyBrandListException();
        }

        return brands.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private int countBrandByCategoryId(int categoryId) {
        return brandMapper.countBrandByCategoryId(categoryId);
    }

    public String updateName(int id, String newName) {
        Brand brand = validateBrand(id);
        brand.updateName(newName);
        brandMapper.updateName(id, newName);
        return newName;
    }

    public void delete(int id) {
        validateBrand(id);
        brandMapper.deleteById(id);
    }

    private Brand validateBrand(int id) {
        return brandMapper.findById(id).orElseThrow(NotFoundBrandException::new);
    }

    private boolean isNameExists(String name) {
        return brandMapper.existsByName(name);
    }

    private BrandDto toDto(Brand brand) {
        return BrandDto.builder()
                .id(brand.getId())
                .categoryId(brand.getCategoryId())
                .name(brand.getName())
                .createdDate(brand.getCreatedDate())
                .updatedDate(brand.getUpdatedDate())
                .build();
    }
}
