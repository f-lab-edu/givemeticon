package com.jinddung2.givemeticon.user.application;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static com.jinddung2.givemeticon.user.constants.SessionConstants.LOGIN_USER;

@Service
@RequiredArgsConstructor
public class SessionLoginService implements LoginService {

    private final HttpSession session;

    @Override
    public void login(String email) {
        session.setAttribute(LOGIN_USER, email);
    }

    @Override
    public void logout() {
        session.removeAttribute(LOGIN_USER);
    }

    @Override
    public Optional<String> getLoginUser() {
        return Optional.ofNullable((String) session.getAttribute(LOGIN_USER));
    }
}
