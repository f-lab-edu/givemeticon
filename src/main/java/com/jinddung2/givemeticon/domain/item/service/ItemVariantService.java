package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.item.domain.ItemVariant;
import com.jinddung2.givemeticon.domain.item.domain.validator.ItemVariantCreateValidator;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.mapper.ItemVariantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ItemVariantService {

    private final ItemVariantMapper itemVariantMapper;
    private final ItemVariantCreateValidator itemVariantCreateValidator;

    public int save(int itemId, int sellerId, ItemVariantCreateRequest request) {
        itemVariantCreateValidator.validate(request);

        ItemVariant itemVariant = request.toEntity();
        itemVariant.updateItemId(itemId);
        itemVariant.updateSellerId(sellerId);

        itemVariantMapper.save(itemVariant);
        return itemVariant.getId();
    }
}
