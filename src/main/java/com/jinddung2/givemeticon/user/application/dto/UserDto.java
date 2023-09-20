package com.jinddung2.givemeticon.user.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jinddung2.givemeticon.user.domain.UserRole;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class UserDto {
    private int accountId;
    private String email;
    private String password;
    private String phone;
    private UserRole userRole;
    private boolean isActive;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime createdDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime updatedDate;
    private LocalDateTime deletedDate;

    @Builder
    public UserDto(int accountId, String email, String password, String phone, UserRole userRole, boolean isActive, LocalDateTime createdDate, LocalDateTime updatedDate, LocalDateTime deletedDate) {
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
}
