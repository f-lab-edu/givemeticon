package com.jinddung2.givemeticon.domain.brand.controller;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandCreateRequest;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandUpdateNameRequest;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/brands")
public class BrandController {

    private final BrandService brandService;

    @PostMapping
    public int create(@RequestBody BrandCreateRequest request) {
        return brandService.save(request);
    }

    @GetMapping("/category/{categoryId}")
    public List<BrandDto> getBrands(@PathVariable(name = "categoryId") int categoryId,
                                                                 @RequestParam(defaultValue = "0") int page) {
        return brandService.getBrands(categoryId, page);
    }

    @GetMapping("/{brandId}")
    public BrandDto getBrand(@PathVariable(name = "brandId") int brandId) {
        return brandService.getBrand(brandId);
    }

    @PutMapping("/{brandId}")
    public String updateName(
            @PathVariable(name = "brandId") int brandId,
            @RequestBody BrandUpdateNameRequest request) {
        String newName = brandService.updateName(brandId, request.name());
        return "Successfully updated brand name: " + newName;
    }

    @DeleteMapping("/{brandId}")
    public String delete(@PathVariable(name = "brandId") int brandId) {
        brandService.delete(brandId);
        return "Successfully delete brand: " + brandId;
    }
}
