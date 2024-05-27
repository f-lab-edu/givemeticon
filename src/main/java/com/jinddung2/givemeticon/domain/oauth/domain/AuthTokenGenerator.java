package com.jinddung2.givemeticon.domain.oauth.domain;

import com.jinddung2.givemeticon.common.security.utils.JwtTokenUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@RequiredArgsConstructor
public class AuthTokenGenerator {
    private static final String BEARER_TYPE = "Bearer";
    private static final long ACCESS_TOKEN_EXPIRE_TIME = 1000 * 60 * 30;            // 30분
    private static final long REFRESH_TOKEN_EXPIRE_TIME = 1000 * 60 * 60 * 24 * 7;  // 7일

    private final JwtTokenUtil jwtTokenUtil;

    public AuthToken generate(Integer userId) {
        long now = (new Date()).getTime();
        Date accessTokenExpiredDate = new Date(now + ACCESS_TOKEN_EXPIRE_TIME);
        Date refreshTokenExpiredDate = new Date(now + REFRESH_TOKEN_EXPIRE_TIME);

        String subject = userId.toString();
        String accessToken = jwtTokenUtil.generate(subject, accessTokenExpiredDate);
        String refreshToken = jwtTokenUtil.generate(subject, refreshTokenExpiredDate);

        return AuthToken.of(accessToken, refreshToken, BEARER_TYPE, ACCESS_TOKEN_EXPIRE_TIME / 1000L);
    }

    public Long extractUserId(String accessToken) {
        return Long.valueOf(jwtTokenUtil.extractSubject(accessToken));
    }
}
