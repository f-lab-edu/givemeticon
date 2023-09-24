package com.jinddung2.givemeticon.oauth.infrastructure.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthProvider;
import com.jinddung2.givemeticon.oauth.domain.oauth.OAuthUserInfo;
import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KaKaoUserInfo implements OAuthUserInfo {

    @JsonProperty("kakao_account")
    private KakaouAccount kakaouAccount;
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaouAccount(String email) {
    }

    @Override
    public String getEmail() {
        return kakaouAccount.email;
    }

    @Override
    public OAuthProvider getOAuthProvider() {
        return OAuthProvider.KAKAO;
    }
}
