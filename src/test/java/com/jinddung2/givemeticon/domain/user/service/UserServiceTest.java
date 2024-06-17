package com.jinddung2.givemeticon.domain.user.service;

import com.jinddung2.givemeticon.domain.account.domain.Account;
import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.LoginRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.domain.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.domain.user.exception.MisMatchPasswordException;
import com.jinddung2.givemeticon.domain.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.domain.user.mapper.UserMapper;
import com.jinddung2.givemeticon.fixture.AccountFixture;
import com.jinddung2.givemeticon.fixture.CashPointFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @InjectMocks
    UserService sut;
    @Mock
    UserMapper userMapper;
    @Mock
    PasswordEncoder passwordEncoder;
    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("회원가입에 성공한다")
    void signUp_Success() {
        SignUpRequest request = new SignUpRequest("test@example.com", "test1234", "01012345678");
        CashPoint cashPointFixture = CashPointFixture.createCashPointFixture();
        String encryptedPassword = "encryptedPassword";
        User userFixture = UserFixture.createUserFixture(request.getEmail(), encryptedPassword, request.getPhone(), cashPointFixture, now);

        when(userMapper.existsByEmail(request.getEmail())).thenReturn(false);
        when(userMapper.existsByPhone(request.getPhone())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn(encryptedPassword);
        when(userMapper.save(any(User.class))).thenReturn(userFixture);

        User result = sut.signUp(request, cashPointFixture.getId());

        verify(userMapper).existsByEmail(request.getEmail());
        verify(userMapper).existsByPhone(request.getPhone());
        assertNotNull(result);
        assertThat(result).isEqualTo(userFixture);
    }

    @Test
    @DisplayName("이메일 중복으로 회원가입에 실패한다")
    void signUp_Fail_DuplicateEmail() {
        SignUpRequest request = new SignUpRequest("test@example.com", "test1234", "01012345678");
        CashPoint cashPointFixture = CashPointFixture.createCashPointFixture();

        when(userMapper.existsByEmail(request.getEmail())).thenReturn(true);

        assertThrows(DuplicatedEmailException.class, () -> sut.signUp(request, cashPointFixture.getId()));
    }

    @Test
    @DisplayName("핸드폰 번호 중복으로 회원가입에 실패한다")
    void signUp_Fail_DuplicatePhone() {
        SignUpRequest request = new SignUpRequest("test@example.com", "test1234", "01012345678");
        CashPoint cashPointFixture = CashPointFixture.createCashPointFixture();

        when(userMapper.existsByPhone(request.getPhone())).thenReturn(true);

        assertThrows(DuplicatedPhoneException.class, () -> sut.signUp(request, cashPointFixture.getId()));
    }

    @Test
    @DisplayName("해당 유저의 id가 존재하는지 확인한다.")
    void exists_By_Id_True() {
        User userFixture = UserFixture.createUserFixture(now);
        when(userMapper.existsById(userFixture.getId())).thenReturn(true);

        boolean exists = sut.isExists(userFixture.getId());

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("해당 유저의 id가 존재하지 않는다.")
    void exists_By_Id_False() {
        User userFixture = UserFixture.createUserFixture(now);
        when(userMapper.existsById(userFixture.getId())).thenReturn(false);

        boolean exists = sut.isExists(userFixture.getId());

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("id를 통해 유저 정보를 갖고 오는데 성공한다.")
    void get_User_Success() {
        User userFixture = UserFixture.createUserFixture(now);
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.of(userFixture));

        UserDto result = sut.getUserInfo(userFixture.getId());

        assertThat(result).isEqualTo(UserDto.of(userFixture));
    }

    @Test
    @DisplayName("id를 찾을 수 없어 유저 정보를 갖고 오는데 실패한다.")
    void get_User_Fail_Not_Exists_Email() {
        User userFixture = UserFixture.createUserFixture(now);

        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.empty());

        assertThrows(NotFoundUserException.class, () -> sut.getUser(userFixture.getId()));
    }

    @Test
    @DisplayName("이메일과 패스워드가 일치하여 로그인 정보를 가져온다.")
    void check_Login_Password_Match_Success() {
        LoginRequest request = new LoginRequest("test@example.com", "test1234");
        User userFixture = UserFixture.createUserFixture(request.getEmail(), request.getPassword(), now);
        when(userMapper.findByEmail(request.getEmail())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.matches(request.getPassword(), userFixture.getPassword())).thenReturn(true);

        UserDto result = sut.checkLogin(request.getEmail(), request.getPassword());

        assertNotNull(result);
        assertThat(result).isEqualTo(UserDto.of(userFixture));
    }

    @Test
    @DisplayName("이메일에 맞는 패스워드가 아니어서 실패한다.")
    void check_Login_Password_Not_Match_Success() {
        LoginRequest request = new LoginRequest("test@example.com", "test1234");
        User userFixture = UserFixture.createUserFixture(now);
        when(userMapper.findByEmail(request.getEmail())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.matches(request.getPassword(), userFixture.getPassword())).thenReturn(false);

        assertThrows(MisMatchPasswordException.class, () -> sut.checkLogin(request.getEmail(), request.getPassword()));
    }

    @Test
    @DisplayName("비밀번호 변경에 성공한다.")
    void update_Password_Success() {
        PasswordUpdateRequest request = new PasswordUpdateRequest("test1234", "newtest1234");
        User userFixture = UserFixture.createUserFixture(now);
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.matches(request.oldPassword(), userFixture.getPassword())).thenReturn(true);
        when(passwordEncoder.encode(request.newPassword())).thenReturn(request.newPassword());

        sut.updatePassword(userFixture.getId(), request);

        verify(userMapper).findById(userFixture.getId());

        assertThat(request.newPassword()).isEqualTo(userFixture.getPassword());
    }

    @Test
    @DisplayName("이전 비밀번호가 불일치하여 비밀번호 변경에 실패한다.")
    void update_Password_Fail_MisMatch_Old_password() {
        User userFixture = UserFixture.createUserFixture(now);
        PasswordUpdateRequest request = new PasswordUpdateRequest("test1234", "newtest1234");
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.of(userFixture));

        when(userFixture.isPasswordMatch(passwordEncoder, request.oldPassword())).thenReturn(false);

        Assertions.assertThrows(MisMatchPasswordException.class,
                () -> sut.updatePassword(userFixture.getId(), request));
    }

    @Test
    @DisplayName("유저 비밀번호를 임시 비밀번호로 바꾼다.")
    public void reset_Password_Success() {
        String tempPassword = "01234567890ab";
        String encryptedPassword = "encryptedPassword";
        User userFixture = UserFixture.createUserFixture(now);

        when(userMapper.findByEmail(userFixture.getEmail())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.encode(tempPassword)).thenReturn(encryptedPassword);
        doNothing().when(userMapper).updatePassword(userFixture.getId(), encryptedPassword);

        sut.resetPassword(userFixture.getEmail(), tempPassword);

        verify(userMapper).findByEmail(userFixture.getEmail());
        verify(userMapper).updatePassword(userFixture.getId(), encryptedPassword);
        assertThat(userFixture.getPassword()).isEqualTo(encryptedPassword);
    }

    @Test
    @DisplayName("유저가 계좌를 등록한다.")
    void update_Account_Id_Success() {
        Account accountFixture = AccountFixture.createAccountFixture();
        User userFixture = UserFixture.createUserFixture(now);
        when(userMapper.findById(1)).thenReturn(Optional.of(userFixture));

        sut.updateAccount(userFixture.getId(), accountFixture.getId());

        verify(userMapper).updateAccount(userFixture.getId(), accountFixture.getId());
        assertThat(userFixture.getAccountId()).isEqualTo(accountFixture.getId());
    }
}