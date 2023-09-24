package com.jinddung2.givemeticon.oauth.application;

import com.jinddung2.givemeticon.oauth.domain.AuthToken;
import com.jinddung2.givemeticon.oauth.domain.AuthTokenGenerator;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthLoginParams;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthUserInfo;
import com.jinddung2.givemeticon.user.domain.User;
import com.jinddung2.givemeticon.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {
    private final UserMapper userMapper;
    private final AuthTokenGenerator authTokenGenerator;
    private final RequestOAuthInfoService requestOAuthInfoService;
    private final PasswordEncoder passwordEncoder;

    public AuthToken login(OAuthLoginParams params) {
        OAuthUserInfo oAuthUserInfo = requestOAuthInfoService.request(params);
        Integer userId = findOrCreateUser(oAuthUserInfo);
        return authTokenGenerator.generate(userId);
    }

    private Integer findOrCreateUser(OAuthUserInfo oAuthUserInfo) {
        return userMapper.findById(oAuthUserInfo.getEmail())
                .map(User::getId)
                .orElseGet(() -> newUser(oAuthUserInfo));
    }

    private Integer newUser(OAuthUserInfo oAuthUserInfo) {
        User user = User.builder()
                .email(oAuthUserInfo.getEmail())
                .password(passwordEncoder.encode(oAuthUserInfo.getEmail()))
                .provider(oAuthUserInfo.getOAuthProvider())
                .isActive(true)
                .build();

        userMapper.save(user);
        return user.getId();
    }
}
