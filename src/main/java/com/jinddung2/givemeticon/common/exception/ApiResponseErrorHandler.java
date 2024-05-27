package com.jinddung2.givemeticon.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

@Slf4j
public class ApiResponseErrorHandler implements ResponseErrorHandler {
    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return !response.getStatusCode().is2xxSuccessful();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody()))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }

        log.error("API 호출 중 에러 발생: HTTP 상태 코드: {}, 응답 본문: {}", response.getStatusCode().value(), body);

        throw new ApiException(response.getStatusCode().value(),
                "API 호출 중 에러 발생: " + response.getStatusCode().value() + " 응답 본문: " + body);
    }
}
