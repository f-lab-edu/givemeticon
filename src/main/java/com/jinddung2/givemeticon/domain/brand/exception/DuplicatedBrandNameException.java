package com.jinddung2.givemeticon.domain.brand.exception;

public class DuplicatedBrandNameException extends BrandException {
    public DuplicatedBrandNameException() {
        super(BrandErrorCode.DUPLICATED_BRAND_NAME);
    }
}
