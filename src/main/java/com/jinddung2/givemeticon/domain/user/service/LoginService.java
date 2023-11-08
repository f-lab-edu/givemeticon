package com.jinddung2.givemeticon.domain.user.service;

import org.springframework.stereotype.Service;

@Service
public interface LoginService {
    void login(int id);

    void logout();

    int getLoginUser();
}
