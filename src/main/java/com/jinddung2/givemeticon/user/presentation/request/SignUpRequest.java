package com.jinddung2.givemeticon.user.presentation.request;

import com.jinddung2.givemeticon.user.domain.User;
import com.jinddung2.givemeticon.user.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class SignUpRequest {
    @NotBlank(message = "이메일 입력은 필수입니다.")
    @Email(message = "이메일 형식에 맞게 입력해 주세요.")
    private String email;
    @NotBlank(message = "패스워드를 입력은 필수입니다.")
    @Size(min = 8, message = "비밀번호는 최소 8자 이상이어야 합니다.")
    private String password;
    @NotBlank(message = "휴대펀 번호 입력은 필수입니다.")
    @Pattern(regexp = "^(010)[0-9]{8}$", message = "휴대폰 번호 형식에 맞게 입력해 주세요.")
    private String phone;

    public SignUpRequest(String email, String password, String phone) {
        this.email = email;
        this.password = password;
        this.phone = phone;
    }

    public User toEntity(String encryptedPassword) {
        return User.builder()
                .email(email)
                .accountId(0)
                .password(encryptedPassword)
                .phone(phone)
                .isActive(true)
                .userRole(UserRole.USER)
                .createdDate(LocalDateTime.now())
                .updatedDate(null)
                .deletedDate(null)
                .build();
    }
}
