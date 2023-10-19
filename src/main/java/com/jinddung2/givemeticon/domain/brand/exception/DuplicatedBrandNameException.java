package com.jinddung2.givemeticon.domain.brand.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class DuplicatedBrandNameException extends BrandException {
    public DuplicatedBrandNameException() {
        super(ErrorCode.DUPLICATED_BRAND_NAME);
    }
}
