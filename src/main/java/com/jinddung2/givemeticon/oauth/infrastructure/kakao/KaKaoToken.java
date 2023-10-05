package com.jinddung2.givemeticon.oauth.infrastructure.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KaKaoToken(
        @JsonProperty("token_type")
        String tokenType,
        @JsonProperty("access_token")
        String accessToken,
        @JsonProperty("expires_in")
        String expiresIn,
        @JsonProperty("refresh_token")
        String refreshToken,
        @JsonProperty("refresh_token_expires_in")
        String refreshTokenExpiresIn,
        @JsonProperty("scope")
        String scope
) {
}
