package com.jinddung2.givemeticon.domain.oauth.domain.oauth;

import org.springframework.util.MultiValueMap;

public interface OAuthLoginParams {
    OAuthProvider oAuthProvider();

    MultiValueMap<String, String> makeBody();
}
