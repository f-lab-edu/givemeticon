package com.jinddung2.givemeticon.oauth.presentation;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.oauth.application.OAuthLoginService;
import com.jinddung2.givemeticon.oauth.domain.AuthToken;
import com.jinddung2.givemeticon.oauth.infrastructure.kakao.KakaoLoginParam;
import com.jinddung2.givemeticon.oauth.infrastructure.naver.NaverLoginParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Slf4j
public class OAuthController {

    private final OAuthLoginService oAuthLoginService;

    @PostMapping("/naver")
    public ResponseEntity<ApiResponse<AuthToken>> naverLogin(@RequestBody NaverLoginParam param) {
        AuthToken authToken = oAuthLoginService.login(param);
        return new ResponseEntity<>(ApiResponse.success(authToken), HttpStatus.OK);
    }

    @PostMapping("/kakao")
    public ResponseEntity<ApiResponse<AuthToken>> kakaoLogin(@RequestBody KakaoLoginParam param) {
        log.info("code={}", param.getAuthorizationCode());
        AuthToken authToken = oAuthLoginService.login(param);
        return new ResponseEntity<>(ApiResponse.success(authToken), HttpStatus.OK);
    }
}
