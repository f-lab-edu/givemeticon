package com.jinddung2.givemeticon.account.presentation.response;

public record OpenBankingToken(
        String access_token,
        String token_type,
        String expires_in,
        String scope,
        String client_use_code
) {
}
