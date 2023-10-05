package com.jinddung2.givemeticon.oauth.domain.oauth;

public interface OAuthClient {
    OAuthProvider oauthProvider();

    String requestAccessToken(OAuthLoginParams params);

    OAuthUserInfo requestOAuthInfo(String accessToken);
}
