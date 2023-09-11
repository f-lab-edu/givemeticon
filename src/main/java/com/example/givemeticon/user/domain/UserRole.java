package com.example.givemeticon.user.domain;

public enum UserRole {
    USER("ROLE_USER"),
    VIP("ROLE_VIP");
    private final String name;

    UserRole(String name) {
        this.name = name;
    }
}
