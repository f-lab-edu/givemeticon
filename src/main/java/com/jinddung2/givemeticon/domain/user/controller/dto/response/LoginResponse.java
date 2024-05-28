package com.jinddung2.givemeticon.domain.user.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jinddung2.givemeticon.domain.oauth.domain.oauth.OAuthProvider;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.domain.UserRole;

import java.time.LocalDateTime;

public record LoginResponse(
        int sessionId,
        int id,
        int accountId,
        int cashPointId,
        String email,
        String phone,
        UserRole userRole,
        boolean isActive,
        OAuthProvider provider,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
        LocalDateTime createdDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
        LocalDateTime updatedDate
) {
    public static LoginResponse of(int sessionId, UserDto userDto) {
        return new LoginResponse(
                sessionId,
                userDto.getId(),
                userDto.getAccountId(),
                userDto.getCashPointId(),
                userDto.getEmail(),
                userDto.getPhone(),
                userDto.getUserRole(),
                userDto.isActive(),
                userDto.getProvider(),
                userDto.getCreatedDate(),
                userDto.getUpdatedDate());
    }
}
