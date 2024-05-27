package com.jinddung2.givemeticon.domain.oauth.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.oauth.infrastructure.kakao.KakaoLoginParam;
import com.jinddung2.givemeticon.domain.oauth.infrastructure.naver.NaverLoginParam;
import com.jinddung2.givemeticon.domain.oauth.service.OAuthLoginService;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
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
    private final LoginService loginService;

    @PostMapping("/naver")
    public ResponseEntity<ApiResponse<Void>> naverLogin(@RequestBody NaverLoginParam param) {
        int userId = oAuthLoginService.login(param);
        loginService.login(userId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @PostMapping("/kakao")
    public ResponseEntity<ApiResponse<Void>> kakaoLogin(@RequestBody KakaoLoginParam param) {
        int userId = oAuthLoginService.login(param);
        loginService.login(userId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }
}
