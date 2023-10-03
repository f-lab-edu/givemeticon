package com.jinddung2.givemeticon.brand.infrastructure.mapper;

import com.jinddung2.givemeticon.brand.domain.Brand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface BrandMapper {
    int save(Brand brand);

    boolean existsByName(String name);

    Optional<Brand> findById(int id);

    void updateName(@Param("id") int id,
                    @Param("newName") String newName);

    void deleteById(int id);
}
