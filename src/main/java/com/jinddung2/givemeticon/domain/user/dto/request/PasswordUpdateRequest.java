package com.jinddung2.givemeticon.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordUpdateRequest(
        @NotBlank(message = "변경 전 패스워드 입력은 필수입니다..")
        String oldPassword,
        @NotBlank(message = "변경 후 패스워드 입력은 필수입니다.")
        @Size(min = 8, message = "비밀번호는 최소 8자 이상이어야 합니다.")
        String newPassword
) {
}
