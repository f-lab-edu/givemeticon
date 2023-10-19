package com.jinddung2.givemeticon.domain.oauth.service;

import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthClient;
import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthLoginParams;
import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthProvider;
import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthUserInfo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RequestOAuthInfoService {

    private final Map<OAuthProvider, OAuthClient> clients;

    public RequestOAuthInfoService(List<OAuthClient> clients) {
        this.clients = clients.stream().collect(
                Collectors.toUnmodifiableMap(OAuthClient::oauthProvider, Function.identity())
        );
    }

    public OAuthUserInfo request(OAuthLoginParams params) {
        OAuthClient client = clients.get(params.oAuthProvider());
        String accessToken = client.requestAccessToken(params);
        return client.requestOAuthInfo(accessToken);
    }
}
