package com.jinddung2.givemeticon.brand.presentation;

import com.jinddung2.givemeticon.brand.application.BrandService;
import com.jinddung2.givemeticon.brand.application.dto.BrandDto;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import com.jinddung2.givemeticon.brand.presentation.request.BrandUpdateNameRequest;
import com.jinddung2.givemeticon.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/brands")
public class BrandController {

    private final BrandService brandService;

    @PostMapping
    public ResponseEntity<ApiResponse<Integer>> create(@RequestBody BrandCreateRequest request) {
        int id = brandService.save(request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

    @GetMapping("/{brandId}")
    public ResponseEntity<ApiResponse<BrandDto>> getBrand(@PathVariable int brandId) {
        BrandDto brandDto = brandService.getBrand(brandId);
        return new ResponseEntity<>(ApiResponse.success(brandDto), HttpStatus.OK);
    }

    @PutMapping("/{brandId}")
    public ResponseEntity<ApiResponse<String>> updateName(
            @PathVariable int brandId,
            @RequestBody BrandUpdateNameRequest request) {
        String updatedName = brandService.updateName(brandId, request.name());
        return new ResponseEntity<>(ApiResponse.success(updatedName), HttpStatus.OK);
    }

    @DeleteMapping("/{brandId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable int brandId) {
        brandService.delete(brandId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.NO_CONTENT);
    }
}
