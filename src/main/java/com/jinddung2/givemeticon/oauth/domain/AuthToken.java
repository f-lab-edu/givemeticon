package com.jinddung2.givemeticon.oauth.domain;

import lombok.Getter;

@Getter
public class AuthToken {
    private String accessToken;
    private String refreshToken;
    private String grantType;
    private Long expiresIn;

    private AuthToken(String accessToken, String refreshToken, String grantType, Long expiresIn) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.grantType = grantType;
        this.expiresIn = expiresIn;
    }

    public static AuthToken of(String accessToken, String refreshToken, String grantType, Long expiresIn) {
        return new AuthToken(accessToken, refreshToken, grantType, expiresIn);
    }
}
