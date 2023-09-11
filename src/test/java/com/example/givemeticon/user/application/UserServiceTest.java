package com.example.givemeticon.user.application;

import com.example.givemeticon.user.domain.User;
import com.example.givemeticon.user.exception.DuplicatedEmailException;
import com.example.givemeticon.user.exception.DuplicatedPhoneException;
import com.example.givemeticon.user.infrastructure.mapper.UserMapper;
import com.example.givemeticon.user.presentation.request.SignUpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @InjectMocks
    UserService userService;
    @Mock
    UserMapper userMapper;
    @Mock
    PasswordEncoder passwordEncoder;
    SignUpRequest signUpRequest;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        signUpRequest = new SignUpRequest("test@naver.com", "test1234", "01012345678");
    }

    @Test
    @DisplayName("회원가입 성공")
    void SignUp_Success() {
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(false);
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(false);

        userService.signUp(signUpRequest);

        verify(userMapper).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 실패 - 이메일 중복")
    void SignUp_Fail_DuplicateEmail() {
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(true);

        assertThrows(DuplicatedEmailException.class, () -> userService.signUp(signUpRequest));
    }

    @Test
    @DisplayName("회원가입 실패 - 핸드폰 번호 중복")
    void SignUp_Fail_DuplicatePhone() {
        when(userMapper.existsByPhone(signUpRequest.getPhone())).thenReturn(true);

        assertThrows(DuplicatedPhoneException.class, () -> userService.signUp(signUpRequest));
    }

}