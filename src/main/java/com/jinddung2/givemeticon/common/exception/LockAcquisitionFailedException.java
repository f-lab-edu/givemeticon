package com.jinddung2.givemeticon.common.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.CommonErrorCode;

public class LockAcquisitionFailedException extends GiveMeTiConException {
    public LockAcquisitionFailedException() {
        super(CommonErrorCode.LOCK_ACQUISITION_FAILED);
    }
}
