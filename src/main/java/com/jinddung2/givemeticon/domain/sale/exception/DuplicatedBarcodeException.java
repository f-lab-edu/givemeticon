package com.jinddung2.givemeticon.domain.sale.exception;

public class DuplicatedBarcodeException extends SaleException {

    public DuplicatedBarcodeException() {
        super(SaleErrorCode.DUPLICATED_BARCODE_NUMBER);
    }
}
