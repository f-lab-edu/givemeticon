package com.jinddung2.givemeticon.domain.user.service;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@Service
@RequiredArgsConstructor
public class SessionLoginService implements LoginService {

    private final HttpSession session;

    @Override
    public void login(int id) {
        session.setAttribute(LOGIN_USER, id);
    }

    @Override
    public void logout() {
        session.removeAttribute(LOGIN_USER);
    }

    @Override
    public int getLoginUser() {
        return (int) session.getAttribute(LOGIN_USER);
    }
}
