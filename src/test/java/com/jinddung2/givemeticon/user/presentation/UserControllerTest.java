package com.jinddung2.givemeticon.user.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.user.application.SessionLoginService;
import com.jinddung2.givemeticon.user.application.UserService;
import com.jinddung2.givemeticon.user.application.dto.UserDto;
import com.jinddung2.givemeticon.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.user.exception.NotFoundEmailException;
import com.jinddung2.givemeticon.user.presentation.request.LoginRequest;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.doThrow;
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
    SessionLoginService loginService;
    SignUpRequest signUpRequest;
    LoginRequest loginRequest;
    UserDto userDto;

    @BeforeEach
    void setUp() {
        signUpRequest = new SignUpRequest("test@example.com", "test1234", "01012345678");
        loginRequest = new LoginRequest("test@example.com", "test1234");
        userDto = UserDto.builder()
                .email("test@example.com")
                .password("test1234")
                .build();
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
                        .get("/api/v1/users/info")
                        .param("email", loginRequest.getEmail())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(userService).getUser(loginRequest.getEmail());
    }

    @Test
    public void get_UserInfo_Fail_Not_Exists_Email() throws Exception {
        doThrow(new NotFoundEmailException()).when(userService).getUser(loginRequest.getEmail());
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/info")
                        .param("email", loginRequest.getEmail())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
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
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(loginService).logout();
    }
}