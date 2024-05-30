package com.jinddung2.givemeticon.domain.brand.exception;

public class NotFoundBrandException extends BrandException {
    public NotFoundBrandException() {
        super(BrandErrorCode.NOT_FOUND_BRAND);
    }
}
