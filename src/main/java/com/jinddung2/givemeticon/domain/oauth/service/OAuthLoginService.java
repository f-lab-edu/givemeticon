package com.jinddung2.givemeticon.domain.oauth.service;

import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthLoginParams;
import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthUserInfo;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {
    private final UserMapper userMapper;
    private final RequestOAuthInfoService requestOAuthInfoService;
    private final PasswordEncoder passwordEncoder;

    public UserDto login(OAuthLoginParams params) {
        OAuthUserInfo oAuthUserInfo = requestOAuthInfoService.request(params);
        return findOrCreateUser(oAuthUserInfo);
    }

    private UserDto findOrCreateUser(OAuthUserInfo oAuthUserInfo) {
        return userMapper.findByEmail(oAuthUserInfo.getEmail())
                .map(UserDto::of)
                .orElseGet(() -> newUser(oAuthUserInfo));
    }

    private UserDto newUser(OAuthUserInfo oAuthUserInfo) {
        User user = User.builder()
                .email(oAuthUserInfo.getEmail())
                .password(passwordEncoder.encode(oAuthUserInfo.getEmail()))
                .provider(oAuthUserInfo.getOAuthProvider())
                .isActive(true)
                .build();

        userMapper.save(user);
        return UserDto.of(user);
    }
}
