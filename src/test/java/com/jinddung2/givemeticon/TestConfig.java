package com.jinddung2.givemeticon;

import com.jinddung2.givemeticon.common.config.controller.hanlder.CustomResponseBodyReturnValueHandler;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

import java.util.ArrayList;
import java.util.List;

@TestConfiguration
public class TestConfig {

    private final RequestMappingHandlerAdapter handlerAdapter;

    public TestConfig(RequestMappingHandlerAdapter handlerAdapter) {
        this.handlerAdapter = handlerAdapter;
    }

    @Bean
    public MockMvcBuilderCustomizer mockMvcBuilderCustomizer() {
        return builder -> {
            List<HandlerMethodReturnValueHandler> originalHandlers = handlerAdapter.getReturnValueHandlers();
            if (originalHandlers != null) {
                List<HandlerMethodReturnValueHandler> newHandlers = new ArrayList<>();
                for (HandlerMethodReturnValueHandler handler : originalHandlers) {
                    if (handler instanceof RequestResponseBodyMethodProcessor) {
                        newHandlers.add(new CustomResponseBodyReturnValueHandler(handler));
                    }
                    newHandlers.add(handler);
                }
                handlerAdapter.setReturnValueHandlers(newHandlers);
            }
        };
    }
}
