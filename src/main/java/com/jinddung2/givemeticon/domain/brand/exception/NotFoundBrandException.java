package com.jinddung2.givemeticon.domain.brand.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundBrandException extends BrandException {
    public NotFoundBrandException() {
        super(ErrorCode.NOT_FOUND_BRAND);
    }
}
