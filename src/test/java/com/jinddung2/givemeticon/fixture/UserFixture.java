package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.domain.UserRole;

import java.time.LocalDateTime;

public class UserFixture {

    public static User createUserFixture() {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .id(1)
                .accountId(2)
                .cashPointId(3)
                .email("test@test.com")
                .password("test1234")
                .phone("01000000000")
                .userRole(UserRole.USER)
                .isActive(true)
                .provider(null)
                .createdDate(now)
                .updatedDate(now)
                .build();
    }

    public static User createUserFixture(String email, String password) {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .id(1)
                .accountId(2)
                .cashPointId(3)
                .email(email)
                .password(password)
                .phone("01000000000")
                .userRole(UserRole.USER)
                .isActive(true)
                .provider(null)
                .createdDate(now)
                .updatedDate(now)
                .build();
    }

    public static User createUserFixture(String email, String password, String phone) {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .id(1)
                .accountId(2)
                .cashPointId(3)
                .email(email)
                .password(password)
                .phone(phone)
                .userRole(UserRole.USER)
                .isActive(true)
                .provider(null)
                .createdDate(now)
                .updatedDate(now)
                .build();
    }

}
