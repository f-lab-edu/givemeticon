package com.jinddung2.givemeticon.domain.brand.exception;

public class EmptyBrandListException extends BrandException {
    public EmptyBrandListException() {
        super(BrandErrorCode.PAGE_NUMBER_HAS_EMPTY_BRAND);
    }
}
