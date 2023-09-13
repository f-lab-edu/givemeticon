package com.jinddung2.givemeticon.user.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class User {
    private int id;
    private int accountId;
    private String email;
    private String password;
    private String phone;
    private UserRole userRole;
    private boolean isActive;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;
    private LocalDateTime deletedDate;

    @Builder
    public User(
            int accountId,
            String email,
            String password,
            String phone,
            UserRole userRole,
            boolean isActive,
            LocalDateTime createdDate,
            LocalDateTime updatedDate,
            LocalDateTime deletedDate
    ) {
        this.accountId = accountId;
        this.email = email;
        this.password = password;
        this.phone = phone;
        this.userRole = userRole;
        this.isActive = isActive;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
        this.deletedDate = deletedDate;
    }

    public void updateUserRole(UserRole userRole) {
        this.userRole = userRole;
    }
}
