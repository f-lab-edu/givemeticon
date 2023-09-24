package com.jinddung2.givemeticon.oauth.infrastructure.kakao;

import com.jinddung2.givemeticon.oauth.constants.OAuthConstant;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthClient;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthLoginParams;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthProvider;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthUserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class KaKaoApiClient implements OAuthClient {

    @Value("${oauth.kakao.url.auth}")
    private String authUrl;
    @Value("${oauth.kakao.url.api}")
    private String apiUrl;
    @Value("${oauth.kakao.client-id}")
    private String clientId;

    private final RestTemplate restTemplate;

    @Override
    public OAuthProvider oauthProvider() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public String requestAccessToken(OAuthLoginParams params) {
        String url = authUrl + "/oauth/token";
        HttpEntity<MultiValueMap<String, String>> request = generateHttpRequest(params);

        KaKaoToken kaKaoToken = restTemplate.postForObject(url, request, KaKaoToken.class);
        Objects.requireNonNull(kaKaoToken);
        return kaKaoToken.accessToken();
    }

    @Override
    public OAuthUserInfo requestOAuthInfo(String accessToken) {
        String url = apiUrl + "/v2/user/me";

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String requestToken = "Bearer " + accessToken;
        httpHeaders.set("Authorization", requestToken);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("property_keys", "[\"kakao_account.email\"]");

        HttpEntity<?> request = new HttpEntity<>(body, httpHeaders);
        return restTemplate.postForObject(url, request, KaKaoUserInfo.class);
    }

    private HttpEntity<MultiValueMap<String, String>> generateHttpRequest(OAuthLoginParams params) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = params.makeBody();
        log.info("age body={}", body);
        body.add("grant_type", OAuthConstant.GRANT_TYPE);
        body.add("client_id", clientId);
        return new HttpEntity<>(body, httpHeaders);
    }
}
