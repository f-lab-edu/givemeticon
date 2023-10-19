package com.jinddung2.givemeticon.domain.oauth.infrastructure.naver;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NaverToken(
        @JsonProperty("access_token")
        String accessToken,
        @JsonProperty("refresh_token")
        String refreshToken,
        @JsonProperty("token_type")
        String tokenType,
        @JsonProperty("expires_in")
        String expiresIn
) {
}