package com.jinddung2.givemeticon.user.service;

import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.dto.request.LoginRequest;
import com.jinddung2.givemeticon.domain.user.dto.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.domain.user.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.domain.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.domain.user.exception.MisMatchPasswordException;
import com.jinddung2.givemeticon.domain.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.domain.user.mapper.UserMapper;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import org.junit.jupiter.api.Assertions;
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
    PasswordUpdateRequest passwordUpdateRequest;
    User testUser;

    UserDto userDto;

    @BeforeEach
    void setUp() {
        signUpRequest = new SignUpRequest("test@example.com", "test1234", "01012345678");
        loginRequest = new LoginRequest("test@example.com", "test1234");
        testUser = User.builder()
                .id(1)
                .email("test@example.com")
                .password(passwordEncoder.encode("test1234"))
                .build();
        userDto = UserDto.builder()
                .id(testUser.getId())
                .email(testUser.getEmail())
                .password(passwordEncoder.encode("test1234"))
                .build();
        passwordUpdateRequest = new PasswordUpdateRequest("test1234", "newtest1234");
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
    @DisplayName("id를 통해 유저 정보를 갖고 오는데 성공한다.")
    void get_User_Success() {
        when(userMapper.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        UserDto user = userService.getUserInfo(testUser.getId());
        assertEquals(testUser.getEmail(), user.getEmail());
    }

    @Test
    @DisplayName("id를 찾을 수 없어 유저 정보를 갖고 오는데 실패한다.")
    void get_User_Fail_Not_Exists_Email() {
        when(userMapper.findById(testUser.getId())).thenReturn(Optional.empty());

        assertThrows(NotFoundUserException.class, () -> userService.getUserInfo(testUser.getId()));
    }

    @Test
    @DisplayName("이메일에 맞는 패스워드인지 확인한다.")
    void check_Login_Password_Match_Success() {
        when(userMapper.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPassword())).thenReturn(true);

        UserDto result = userService.checkLogin(loginRequest.getEmail(), loginRequest.getPassword());

        assertNotNull(result);
        assertEquals(loginRequest.getEmail(), userDto.getEmail());
    }

    @Test
    @DisplayName("이메일에 맞는 패스워드가 아니어서 실패한다.")
    void check_Login_Password_Not_Match_Success() {
        when(userMapper.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPassword())).thenReturn(false);

        assertThrows(MisMatchPasswordException.class, () -> userService.checkLogin(loginRequest.getEmail(), loginRequest.getPassword()));
    }

    @Test
    @DisplayName("비밀번호 변경에 성공한다.")
    void update_Password_Success() {
        // given
        when(userMapper.findById(1)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(passwordUpdateRequest.oldPassword(), testUser.getPassword())).thenReturn(true);
        when(passwordEncoder.encode(passwordUpdateRequest.newPassword())).thenReturn(passwordUpdateRequest.newPassword());
        // when
        userService.updatePassword(1, passwordUpdateRequest);
        // then
        verify(userMapper).findById(1);
        verify(userMapper).updatePassword(1, passwordUpdateRequest.newPassword());

        Assertions.assertEquals(passwordUpdateRequest.newPassword(), testUser.getPassword());
    }

    @Test
    @DisplayName("이전 비밀번호 불일치로 인해 비밀번호 변경에 실패한다.")
    void update_Password_Fail_MisMatch_Old_password() {
        when(userMapper.findById(1)).thenReturn(Optional.of(testUser));
        when(testUser.isPasswordMatch(passwordEncoder, passwordUpdateRequest.oldPassword())).thenReturn(false);

        Assertions.assertThrows(MisMatchPasswordException.class,
                () -> userService.updatePassword(1, passwordUpdateRequest));
    }

    @Test
    @DisplayName("유저 비밀번호를 임시 비밀번호로 바꾼다.")
    public void reset_Password_Success() {
        String tempPassword = "01234567890ab";

        when(userMapper.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode(tempPassword)).thenReturn(tempPassword);
        doNothing().when(userMapper).updatePassword(testUser.getId(), tempPassword);

        userService.resetPassword(testUser.getEmail(), tempPassword);

        verify(userMapper).findByEmail(testUser.getEmail());
        verify(userMapper).updatePassword(testUser.getId(), tempPassword);

        Assertions.assertEquals(tempPassword, testUser.getPassword());
    }

    @Test
    @DisplayName("해당 유저의 등록된 계좌로 일치시킨다.")
    void update_Account_Id_Success() {
        int accountId = 100;
        when(userMapper.findById(1)).thenReturn(Optional.of(testUser));
        userService.updateAccount(testUser.getId(), accountId);

        verify(userMapper).updateAccount(testUser.getId(), accountId);

        Assertions.assertEquals(100, testUser.getAccountId());
    }
}