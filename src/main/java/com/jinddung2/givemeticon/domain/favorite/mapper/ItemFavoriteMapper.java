package com.jinddung2.givemeticon.domain.favorite.mapper;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ItemFavoriteMapper {

    void save(ItemFavorite itemFavorite);

    Optional<ItemFavorite> findByIdByUserIDAndItemId(@Param("userId") int userId,
                                                     @Param("itemId") int itemId);

    List<ItemFavorite> findFavoritesByUserId(int userId);

    void deleteById(int id);
}
