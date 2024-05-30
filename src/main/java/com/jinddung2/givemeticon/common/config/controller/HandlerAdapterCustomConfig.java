package com.jinddung2.givemeticon.common.config.controller;

import com.jinddung2.givemeticon.common.config.controller.hanlder.CustomResponseBodyReturnValueHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class HandlerAdapterCustomConfig {

    private final RequestMappingHandlerAdapter handlerAdapter;

    @PostConstruct
    public void postConstruct() {
        addCustomResponseBodyReturnValueHandler();
    }

    private void addCustomResponseBodyReturnValueHandler() {
        List<HandlerMethodReturnValueHandler> originalHandlers = handlerAdapter.getReturnValueHandlers();
        List<HandlerMethodReturnValueHandler> newReturnValueHandlers = new ArrayList<>();

        if (originalHandlers != null) {
            for (HandlerMethodReturnValueHandler handler : originalHandlers) {
                if (handler instanceof RequestResponseBodyMethodProcessor) {
                    newReturnValueHandlers.add(new CustomResponseBodyReturnValueHandler(handler));
                }
                newReturnValueHandlers.add(handler);
            }
            handlerAdapter.setReturnValueHandlers(newReturnValueHandlers);
        }
    }
}
