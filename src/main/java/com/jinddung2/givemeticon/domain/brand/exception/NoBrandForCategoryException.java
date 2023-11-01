package com.jinddung2.givemeticon.domain.brand.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NoBrandForCategoryException extends BrandException {

    public NoBrandForCategoryException() {
        super(ErrorCode.NO_BRANDS_FOR_CATEGORY);
    }
}
