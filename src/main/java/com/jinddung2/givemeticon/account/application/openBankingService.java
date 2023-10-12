package com.jinddung2.givemeticon.account.application;

import com.jinddung2.givemeticon.account.presentation.response.OpenBankingToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;

@Slf4j
@Service
public class openBankingService {
    private final String OPENAPI_TOKEN_URI = "https://testapi.openbanking.or.kr/oauth/2.0/token";
    private final int OPEN_BANKING_VERIFICATION_LIMIT_IN_SECONDS = 3600;
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${openapi.client-id}")
    private String clientId;
    @Value("${openapi.client-secret}")
    private String clientSecret;

    public openBankingService(RestTemplate restTemplate, RedisTemplate<String, Object> redisTemplate) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
    }

    public OpenBankingToken requestAccessToken() {
        URI uri = URI.create(OPENAPI_TOKEN_URI);

        HttpEntity<MultiValueMap<String, String>> request = generateHttpRequest();

        OpenBankingToken openBankingToken = null;

        try {
            openBankingToken = restTemplate.postForObject(uri, request, OpenBankingToken.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("HTTP Error={}", e.getStatusText());
            log.error("Response body={}", e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("OpenBankingService.openBanking() error occurred={} ", e.getMessage(), e);
        }

        redisTemplate.opsForValue().set(openBankingToken.client_use_code(),
                openBankingToken.access_token(),
                OPEN_BANKING_VERIFICATION_LIMIT_IN_SECONDS);

        return openBankingToken;
    }

    private HttpEntity<MultiValueMap<String, String>> generateHttpRequest() {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("scope", "oob");
        body.add("grant_type", "client_credentials");

        return new HttpEntity<>(body, httpHeaders);
    }
}
