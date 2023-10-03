package com.jinddung2.givemeticon.brand.infrastructure.mapper;

import com.jinddung2.givemeticon.brand.domain.Brand;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BrandMapper {
    int save(Brand brand);

    boolean existsByName(String name);
}
