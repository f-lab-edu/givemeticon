package com.jinddung2.givemeticon.common.config.controller.hanlder;

import com.jinddung2.givemeticon.common.config.controller.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

@RequiredArgsConstructor
public class CustomResponseBodyReturnValueHandler implements HandlerMethodReturnValueHandler {

    private final HandlerMethodReturnValueHandler delegate;

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return delegate.supportsReturnType(returnType);
    }

    @Override
    public void handleReturnValue(
            Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest) throws Exception {
        ApiResponse<?> realReturnValue;
        if (returnValue == null) {
            realReturnValue = ApiResponse.success();
        } else {
            realReturnValue = ApiResponse.success(returnValue);
        }

        delegate.handleReturnValue(realReturnValue, returnType, mavContainer, webRequest);
    }
}
