package com.jinddung2.givemeticon.user.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.oauth.infrastructure.JwtTokenProvider;
import com.jinddung2.givemeticon.mail.application.MailSendService;
import com.jinddung2.givemeticon.user.application.LoginService;
import com.jinddung2.givemeticon.user.application.PasswordResetAdapter;
import com.jinddung2.givemeticon.user.application.UserService;
import com.jinddung2.givemeticon.user.application.dto.UserDto;
import com.jinddung2.givemeticon.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.user.exception.MisMatchPasswordException;
import com.jinddung2.givemeticon.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.user.presentation.request.LoginRequest;
import com.jinddung2.givemeticon.user.presentation.request.PasswordResetRequest;
import com.jinddung2.givemeticon.user.presentation.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
public class UserControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper; // JSON 객체로 변환하기 위한 ObjectMapper
    @MockBean
    UserService userService; // MockBean으로 UserService 주입
    @MockBean
    LoginService loginService;
    @MockBean
    JwtTokenProvider jwtTokenProvider;
    @MockBean
    MailSendService mailSendService;
    @MockBean
    PasswordResetAdapter passwordResetAdapter;

    @MockBean
    MockHttpSession mockHttpSession;
    SignUpRequest signUpRequest;
    LoginRequest loginRequest;
    UserDto userDto;
    PasswordUpdateRequest passwordUpdateRequest;
    PasswordResetRequest passwordResetRequest;

    @BeforeEach
    void setUp() {
        signUpRequest = new SignUpRequest("test1234@example.com", "test1234", "01012345678");
        loginRequest = new LoginRequest("test1234@example.com", "test1234");
        userDto = UserDto.builder()
                .email("test1234@example.com")
                .password("test1234")
                .build();
        mockHttpSession = new MockHttpSession();
        passwordUpdateRequest = new PasswordUpdateRequest("test1234", "newtest1234");
        passwordResetRequest = new PasswordResetRequest("test1234@example.com");
        when(loginService.getLoginUser()).thenReturn(Optional.of(userDto.getEmail()));
    }

    @Test
    public void signUp_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isCreated());

        // Verify
        Mockito.verify(userService).signUp(signUpRequest);
    }

    @Test
    public void signUp_Fail_Duplicate_Email() throws Exception {
        doThrow(new DuplicatedEmailException()).when(userService).signUp(signUpRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                .post("/api/v1/users/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest)));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 존재하는 이메일입니다."));
    }

    @Test
    public void signUp_Fail_Duplicate_Phone() throws Exception {
        doThrow(new DuplicatedPhoneException()).when(userService).signUp(signUpRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 존재하는 휴대폰 번호입니다."));
    }

    @Test
    public void get_UserInfo_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/1/info")
                        .param("email", loginRequest.getEmail())
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andDo(print())
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(userService).getUserInfo(1);
    }

    @Test
    public void get_UserInfo_Fail_Not_Exists() throws Exception {
        doThrow(new NotFoundUserException()).when(userService).getUserInfo(1);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/1/info")
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // Verify
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("존재하지 않는 회원입니다."));
    }

    @Test
    public void login_Success() throws Exception {
        given(userService.checkLogin(loginRequest.getEmail(), loginRequest.getPassword())).willReturn(userDto);
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(loginService).login(loginRequest.getEmail());
    }

    @Test
    public void logout_Success() throws Exception {
        willDoNothing().given(loginService).logout();
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(loginService).logout();
    }

    @Test
    public void update_Password_Success() throws Exception {
        doNothing().when(userService).updatePassword(1, passwordUpdateRequest);
        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/1/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordUpdateRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(userService).updatePassword(1, passwordUpdateRequest);
    }

    @Test
    public void update_Password_Fail_Not_Exists_User() throws Exception {
        doThrow(new NotFoundUserException()).when(userService).updatePassword(1, passwordUpdateRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/1/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordUpdateRequest)))
                .andExpect(status().isBadRequest());

        // Verify
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("존재하지 않는 회원입니다."));
    }

    @Test
    public void update_Password_MisMatch_Password() throws Exception {
        doThrow(new MisMatchPasswordException()).when(userService).updatePassword(1, passwordUpdateRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/1/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordUpdateRequest)))
                .andExpect(status().isBadRequest());

        // Verify
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("패스워드가 일치하지 않습니다."));

    }

    @Test
    public void send_Temporary_Password() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/users/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordResetRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(passwordResetAdapter).resetPasswordAndSendEmail(passwordResetRequest.email());
    }
}