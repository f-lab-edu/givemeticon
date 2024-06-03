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
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.AccountFixture;
import com.jinddung2.givemeticon.fixture.CashPointFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.BeforeEach;
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

    SignUpRequest signUpRequest;
    CashPoint cashPointFixture;
    User userFixture;

    @BeforeEach
    void setUp() {
        signUpRequest = new SignUpRequest("test@example.com", "test1234", "01012345678");
        cashPointFixture = CashPointFixture.createCashPointFixture();
        userFixture = UserFixture.createUserFixture(signUpRequest.getEmail(), "encryptedPassword", signUpRequest.getPhone(), cashPointFixture, now);
    }

    @Test
    @DisplayName("회원가입에 성공한다")
    void signUp_Success() {
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(false);
        when(userMapper.existsByPhone(signUpRequest.getPhone())).thenReturn(false);
        when(passwordEncoder.encode(signUpRequest.getPassword())).thenReturn("encryptedPassword");
        when(userMapper.save(any(User.class))).thenReturn(userFixture);

        User result = sut.signUp(signUpRequest, cashPointFixture.getId());

        verify(userMapper).existsByEmail(signUpRequest.getEmail());
        verify(userMapper).existsByPhone(signUpRequest.getPhone());
        assertNotNull(result, "The result should not be null");
        assertThat(result).isEqualTo(userFixture);
    }

    @Test
    @DisplayName("이메일 중복으로 회원가입에 실패한다")
    void signUp_Fail_DuplicateEmail() {
        when(userMapper.existsByEmail(signUpRequest.getEmail())).thenReturn(true);

        assertThrows(DuplicatedEmailException.class, () -> sut.signUp(signUpRequest, cashPointFixture.getId()));
    }

    @Test
    @DisplayName("핸드폰 번호 중복으로 회원가입에 실패한다")
    void signUp_Fail_DuplicatePhone() {
        when(userMapper.existsByPhone(signUpRequest.getPhone())).thenReturn(true);

        assertThrows(DuplicatedPhoneException.class, () -> sut.signUp(signUpRequest, cashPointFixture.getId()));
    }

    @Test
    @DisplayName("해당 유저의 id가 존재하는지 확인한다.")
    void exists_By_Id_True() {
        when(userMapper.existsById(userFixture.getId())).thenReturn(true);

        boolean exists = sut.isExists(userFixture.getId());

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("해당 유저의 id가 존재하지 않는다.")
    void exists_By_Id_False() {
        when(userMapper.existsById(userFixture.getId())).thenReturn(false);

        boolean exists = sut.isExists(userFixture.getId());

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("id를 통해 유저 정보를 갖고 오는데 성공한다.")
    void get_User_Success() {
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.of(userFixture));

        UserDto result = sut.getUserInfo(userFixture.getId());

        assertThat(result).isEqualTo(UserDto.of(userFixture));
    }

    @Test
    @DisplayName("id를 찾을 수 없어 유저 정보를 갖고 오는데 실패한다.")
    void get_User_Fail_Not_Exists_Email() {
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.empty());

        assertThrows(NotFoundUserException.class, () -> sut.getUser(userFixture.getId()));
    }

    @Test
    @DisplayName("이메일과 패스워드가 일치하여 로그인 정보를 가져온다.")
    void check_Login_Password_Match_Success() {
        LoginRequest request = new LoginRequest("test@example.com", "test1234");
        when(userMapper.findByEmail(request.getEmail())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.matches(request.getPassword(), userFixture.getPassword())).thenReturn(true);

        UserDto result = sut.checkLogin(request.getEmail(), request.getPassword());

        assertNotNull(result, "The result should not be null");
        assertThat(result).isEqualTo(UserDto.of(userFixture));
    }

    @Test
    @DisplayName("이메일에 맞는 패스워드가 아니어서 실패한다.")
    void check_Login_Password_Not_Match_Success() {
        LoginRequest request = new LoginRequest("test@example.com", "test1234");
        when(userMapper.findByEmail(request.getEmail())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.matches(request.getPassword(), userFixture.getPassword())).thenReturn(false);

        assertThrows(MisMatchPasswordException.class, () -> sut.checkLogin(request.getEmail(), request.getPassword()));
    }

    @Test
    @DisplayName("비밀번호 변경에 성공한다.")
    void update_Password_Success() {
        PasswordUpdateRequest request = new PasswordUpdateRequest("test1234", "newtest1234");
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.matches(request.oldPassword(), userFixture.getPassword())).thenReturn(true);
        when(passwordEncoder.encode(request.newPassword())).thenReturn("encryptedNewPassword");

        sut.updatePassword(userFixture.getId(), request);

        verify(userMapper).updatePassword(userFixture.getId(), "encryptedNewPassword");
        assertThat(userFixture.getPassword()).isEqualTo("encryptedNewPassword");
    }

    @Test
    @DisplayName("이전 비밀번호가 불일치하여 비밀번호 변경에 실패한다.")
    void update_Password_Fail_MisMatch_Old_password() {
        PasswordUpdateRequest request = new PasswordUpdateRequest("test1234", "newtest1234");
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.matches(request.oldPassword(), userFixture.getPassword())).thenReturn(false);

        assertThrows(MisMatchPasswordException.class, () -> sut.updatePassword(userFixture.getId(), request));
    }

    @Test
    @DisplayName("유저 비밀번호를 임시 비밀번호로 바꾼다.")
    void reset_Password_Success() {
        String tempPassword = "01234567890ab";
        String encryptedPassword = "encryptedPassword";

        when(userMapper.findByEmail(userFixture.getEmail())).thenReturn(Optional.of(userFixture));
        when(passwordEncoder.encode(tempPassword)).thenReturn(encryptedPassword);

        sut.resetPassword(userFixture.getEmail(), tempPassword);

        verify(userMapper).updatePassword(userFixture.getId(), encryptedPassword);
        assertThat(userFixture.getPassword()).isEqualTo(encryptedPassword);
    }

    @Test
    @DisplayName("유저가 계좌를 등록한다.")
    void update_Account_Id_Success() {
        Account accountFixture = AccountFixture.createAccountFixture();
        when(userMapper.findById(userFixture.getId())).thenReturn(Optional.of(userFixture));

        sut.updateAccount(userFixture.getId(), accountFixture.getId());

        verify(userMapper).updateAccount(userFixture.getId(), accountFixture.getId());
        assertThat(userFixture.getAccountId()).isEqualTo(accountFixture.getId());
    }
}
