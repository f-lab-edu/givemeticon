package com.jinddung2.givemeticon.category.infrastructure.mapper;

import com.jinddung2.givemeticon.category.domain.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CategoryMapper {
    Optional<Category> findById(int id);

    void updateName(@Param("id") int id,
                    @Param("newName") String newName);

    void deleteById(int id);
}
