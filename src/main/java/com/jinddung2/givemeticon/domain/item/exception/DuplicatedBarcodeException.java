package com.jinddung2.givemeticon.domain.item.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class DuplicatedBarcodeException extends ItemVariantException {

    public DuplicatedBarcodeException() {
        super(ErrorCode.DUPLICATED_BARCODE_NUMBER);
    }
}
