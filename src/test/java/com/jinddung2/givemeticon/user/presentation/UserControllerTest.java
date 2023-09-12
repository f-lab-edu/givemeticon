package com.jinddung2.givemeticon.user.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.user.application.UserService;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@WebMvcTest(UserController.class)
public class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper; // JSON 객체로 변환하기 위한 ObjectMapper

    @MockBean
    UserService userService; // MockBean으로 UserService 주입
    SignUpRequest signUpRequest;
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        signUpRequest = new SignUpRequest("test@naver.com", "test1234", "01012345678");
    }

    @Test
    public void signUp_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(MockMvcResultMatchers.status().isCreated());

        // Verify
        Mockito.verify(userService, Mockito.times(1)).signUp(signUpRequest); // signUp 메서드가 한 번 호출되었는지 확인
    }
}