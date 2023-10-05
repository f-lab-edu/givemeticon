package com.jinddung2.givemeticon.oauth.infrastructure.naver;

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
public class NaverApiClient implements OAuthClient {

    @Value("${oauth.naver.url.auth}")
    private String authUrl;
    @Value("${oauth.naver.url.api}")
    private String apiUrl;
    @Value("${oauth.naver.client-id}")
    private String clientId;
    @Value("${oauth.naver.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate;

    @Override
    public OAuthProvider oauthProvider() {
        return OAuthProvider.NAVER;
    }

    @Override
    public String requestAccessToken(OAuthLoginParams params) {
        String url = authUrl + "/oauth2.0/token";
        HttpEntity<MultiValueMap<String, String>> request = generateHttpRequest(params);

        NaverToken naverToken = restTemplate.postForObject(url, request, NaverToken.class);

        log.info("url={}", url);
        log.info("request={}", request);
        Objects.requireNonNull(naverToken);
        return naverToken.accessToken();
    }

    @Override
    public OAuthUserInfo requestOAuthInfo(String accessToken) {
        String url = apiUrl + "/v1/nid/me";

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String requestToken = "Bearer " + accessToken;
        httpHeaders.set("Authorization", requestToken);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();

        HttpEntity<?> request = new HttpEntity<>(body, httpHeaders);
        log.info("request hearer={}, body={}", request.getHeaders(), request.getBody());
        return restTemplate.postForObject(url, request, NaverUserInfo.class);
    }

    /**
     * "https://nid.naver.com/oauth2.0/token?
     * grant_type=authorization_code (추가할 param)
     * &client_id=jyvqXeaVOVmV (추가할 param)
     * &client_secret=527300A0_COq1_XV33cf (추가할 param)
     * &code=EIc5bFrl4RibFls (NaverLoginParams에 포함되어 있음)
     * 1&state=9kgsGTfH4j7IyAkg" (NaverLoginParams에 포함되어 있음)
     */
    private HttpEntity<MultiValueMap<String, String>> generateHttpRequest(OAuthLoginParams params) {
        HttpHeaders httpHeaders = new HttpHeaders();
        // 접근 토큰 갱신 / 삭제 요청시 access_token 값은 URL 인코딩하셔야 합니다.
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = params.makeBody();
        log.info("age body={}", body);
        body.add("grant_type", OAuthConstant.GRANT_TYPE);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        return new HttpEntity<>(body, httpHeaders);
    }
}
