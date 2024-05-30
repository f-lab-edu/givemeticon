package com.jinddung2.givemeticon.domain.oauth.controller;

import com.jinddung2.givemeticon.domain.oauth.infrastructure.kakao.KakaoLoginParam;
import com.jinddung2.givemeticon.domain.oauth.infrastructure.naver.NaverLoginParam;
import com.jinddung2.givemeticon.domain.oauth.service.OAuthLoginService;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.controller.dto.response.LoginResponse;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    public LoginResponse naverLogin(@RequestBody NaverLoginParam param) {
        UserDto userDto = oAuthLoginService.login(param);
        int sessionId = loginService.login(userDto.getId());
        return LoginResponse.of(sessionId, userDto);
    }

    @PostMapping("/kakao")
    public LoginResponse kakaoLogin(@RequestBody KakaoLoginParam param) {
        UserDto userDto = oAuthLoginService.login(param);
        int sessionId = loginService.login(userDto.getId());
        return LoginResponse.of(sessionId, userDto);
    }
}
