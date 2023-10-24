package com.jinddung2.givemeticon.domain.brand.mapper;

import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface BrandMapper {
    int save(Brand brand);

    boolean existsByName(String name);

    Optional<Brand> findById(int id);

    void updateName(@Param("id") int id,
                    @Param("newName") String newName);

    void deleteById(int id);

    List<Brand> findAllByCategory(Map<String, Object> paramMap);

    int countBrandByCategoryId(@Param("categoryId") int categoryId);
}
