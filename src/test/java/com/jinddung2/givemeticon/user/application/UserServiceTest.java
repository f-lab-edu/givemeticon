package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.user.application.dto.UserDto;
import com.jinddung2.givemeticon.user.domain.User;
import com.jinddung2.givemeticon.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.user.exception.MisMatchPasswordException;
import com.jinddung2.givemeticon.user.exception.NotFoundEmailException;
import com.jinddung2.givemeticon.user.infrastructure.mapper.UserMapper;
import com.jinddung2.givemeticon.user.presentation.request.LoginRequest;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
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
    LoginRequest loginRequest;
    User testUser;

    UserDto userDto;

    @BeforeEach
    void setUp() {
        signUpRequest = new SignUpRequest("test@example.com", "test1234", "01012345678");
        loginRequest = new LoginRequest("test@example.com", "test1234");
        testUser = User.builder()
                .email("test@example.com")
                .password(passwordEncoder.encode("test1234"))
                .build();
        userDto = UserDto.builder()
                .email("test@example.com")
                .password(passwordEncoder.encode("test1234"))
                .build();
    }

    @Test
    @DisplayName("회원가입에 성공한다")
    void signUp_Success() {
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(false);
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(false);

        userService.signUp(signUpRequest);

        verify(userMapper).save(any(User.class));
    }

    @Test
    @DisplayName("이메일 중복으로 회원가입에 실패한다")
    void signUp_Fail_DuplicateEmail() {
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(true);

        assertThrows(DuplicatedEmailException.class, () -> userService.signUp(signUpRequest));
    }

    @Test
    @DisplayName("핸드폰 번호 중복으로 회원가입에 실패한다")
    void signUp_Fail_DuplicatePhone() {
        when(userMapper.existsByPhone(signUpRequest.getPhone())).thenReturn(true);

        assertThrows(DuplicatedPhoneException.class, () -> userService.signUp(signUpRequest));
    }

    @Test
    @DisplayName("이메일을 통해 유저 정보를 갖고 오는데 성공한다.")
    void get_User_Success() {
        when(userMapper.findById(testUser.getEmail())).thenReturn(Optional.of(testUser));
        UserDto user = userService.getUser(testUser.getEmail());
        assertEquals(testUser.getEmail(), user.getEmail());
    }

    @Test
    @DisplayName("이메일을 찾을 수 없어 유저 정보를 갖고 오는데 실패한다.")
    void get_User_Fail_Not_Exists_Email() {
        when(userMapper.findById(testUser.getEmail())).thenReturn(Optional.empty());

        assertThrows(NotFoundEmailException.class, () -> userService.getUser(testUser.getEmail()));
    }

    @Test
    @DisplayName("이메일에 맞는 패스워드인지 확인한다.")
    void check_Login_Password_Match_Success() {
        when(userMapper.findById(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPassword())).thenReturn(true);

        UserDto result = userService.checkLogin(loginRequest.getEmail(), loginRequest.getPassword());

        assertNotNull(result);
        assertEquals(loginRequest.getEmail(), userDto.getEmail());
    }

    @Test
    @DisplayName("이메일에 맞는 패스워드가 아니어서 실패한다.")
    void check_Login_Password_Not_Match_Success() {
        when(userMapper.findById(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPassword())).thenReturn(false);

        assertThrows(MisMatchPasswordException.class, () -> userService.checkLogin(loginRequest.getEmail(), loginRequest.getPassword()));
    }
}