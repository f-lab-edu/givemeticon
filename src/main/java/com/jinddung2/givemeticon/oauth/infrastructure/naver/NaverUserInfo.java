package com.jinddung2.givemeticon.oauth.infrastructure.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthProvider;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthUserInfo;
import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverUserInfo implements OAuthUserInfo {

    @JsonProperty(value = "response")
    private Response response;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String email) {
    }

    @Override
    public String getEmail() {
        return response.email;
    }

    @Override
    public OAuthProvider getOAuthProvider() {
        return OAuthProvider.NAVER;
    }
}
