package com.jinddung2.givemeticon.domain.oauth.domain.oauth;

public interface OAuthClient {
    OAuthProvider oauthProvider();

    String requestAccessToken(OAuthLoginParams params);

    OAuthUserInfo requestOAuthInfo(String accessToken);
}
