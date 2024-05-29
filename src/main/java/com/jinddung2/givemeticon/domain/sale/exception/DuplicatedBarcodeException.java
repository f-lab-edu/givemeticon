package com.jinddung2.givemeticon.domain.sale.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;

public class DuplicatedBarcodeException extends SaleException {

    public DuplicatedBarcodeException() {
        super(ErrorCode.DUPLICATED_BARCODE_NUMBER);
    }
}
