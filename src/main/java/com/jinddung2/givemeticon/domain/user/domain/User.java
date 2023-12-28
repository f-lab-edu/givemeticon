package com.jinddung2.givemeticon.domain.user.domain;

import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthProvider;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@ToString
public class User {
    private int id;
    private int accountId;
    private int cashPointId;
    private String email;
    private String password;
    private String phone;
    private UserRole userRole;
    private boolean isActive;
    private OAuthProvider provider;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;
    private LocalDateTime deletedDate;

    @Builder
    public User(int id, int accountId, int cashPointId,
            String email, String password, String phone, UserRole userRole,
            boolean isActive, OAuthProvider provider,
            LocalDateTime createdDate, LocalDateTime updatedDate, LocalDateTime deletedDate
    ) {
        this.id = id;
        this.accountId = accountId;
        this.cashPointId = cashPointId;
        this.email = email;
        this.password = password;
        this.phone = phone;
        this.userRole = userRole;
        this.isActive = isActive;
        this.provider = provider;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
        this.deletedDate = deletedDate;
    }

    public void updateUserRole(UserRole userRole) {
        this.userRole = userRole;
    }
    public void setUpCashPoint(int pointId) {this.cashPointId = pointId;}

    public void updatePassword(String encryptedPassword) {
        this.password = encryptedPassword;
    }

    public void createAccount(int accountId) {
        this.accountId = accountId;
    }

    public boolean isPasswordMatch(PasswordEncoder passwordEncoder, String inputPassword) {
        return passwordEncoder.matches(inputPassword, this.password);
    }
}
