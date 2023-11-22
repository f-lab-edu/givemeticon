package com.jinddung2.givemeticon.common.utils.constants;

import lombok.Getter;

@Getter
public enum PageSize {
    ITEM(9),
    TRADE(6),
    SALE(6);

    private final int size;

    PageSize(int size) {
        this.size = size;
    }
}
