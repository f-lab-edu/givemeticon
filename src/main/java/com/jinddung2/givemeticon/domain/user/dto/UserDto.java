package com.jinddung2.givemeticon.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthProvider;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.domain.UserRole;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class UserDto {
    private int id;
    private int accountId;
    private String email;
    private String password;
    private String phone;
    private UserRole userRole;
    private boolean isActive;
    private OAuthProvider provider;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime createdDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime updatedDate;
    private LocalDateTime deletedDate;

    @Builder
    public UserDto(int id, int accountId, String email, String password, String phone, UserRole userRole, boolean isActive, OAuthProvider provider, LocalDateTime createdDate, LocalDateTime updatedDate, LocalDateTime deletedDate) {
        {
            this.id = id;
            this.accountId = accountId;
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
    }

    public static UserDto of(User user) {
        return UserDto.builder()
                .id(user.getId())
                .accountId(user.getAccountId())
                .email(user.getEmail())
                .phone(user.getPhone())
                .userRole(user.getUserRole())
                .provider(user.getProvider())
                .createdDate(user.getCreatedDate())
                .updatedDate(user.getUpdatedDate())
                .deletedDate(user.getDeletedDate())
                .isActive(user.isActive())
                .build();
    }
}
