package com.jinddung2.givemeticon.domain.brand.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class EmptyBrandListException extends BrandException {
    public EmptyBrandListException() {
        super(ErrorCode.PAGE_NUMBER_HAS_EMPTY_BRAND);
    }
}
