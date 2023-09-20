package com.jinddung2.givemeticon.user.application;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public interface LoginService {
    void login(String email);

    void logout();

    Optional<String> getLoginUser();
}
