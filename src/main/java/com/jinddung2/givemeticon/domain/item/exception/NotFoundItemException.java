package com.jinddung2.givemeticon.domain.item.exception;

public class NotFoundItemException extends ItemException {
    public NotFoundItemException() {
        super(ItemErrorCode.NOT_FOUND_ITEM);
    }
}
