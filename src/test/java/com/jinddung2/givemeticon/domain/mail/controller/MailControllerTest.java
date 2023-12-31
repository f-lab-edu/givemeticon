package com.jinddung2.givemeticon.domain.mail.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.security.provider.JwtTokenProvider;
import com.jinddung2.givemeticon.domain.mail.exception.EmailNotFoundException;
import com.jinddung2.givemeticon.domain.mail.exception.InvalidCertificationNumberException;
import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
import com.jinddung2.givemeticon.domain.mail.service.MailVerifyService;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MailController.class)
class MailControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    MailSendService mailSendService;

    @MockBean
    MailVerifyService mailVerifyService;

    @MockBean
    LoginService loginService;

    @MockBean
    JwtTokenProvider jwtTokenProvider;

    String email;
    String certificationNumber;

    @BeforeEach
    void setUp() {
        email = "test@example.com";
        certificationNumber = "123456";
    }

    @Test
    @DisplayName("메일에 임시번호 전송에 성공한다.")
    public void send_CertificationNumber_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/mails/send-certification")
                        .content("{\"email\":\"test@example.com\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(mailSendService).sendEmailForCertification(email);
    }

    @Test
    @DisplayName("메일로 전송했던 임시번호 검증에 성공한다.")
    public void verify_CertificationNumber_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/mails/verify")
                        .param("email", email)
                        .param("certificationNumber", certificationNumber))
                .andExpect(status().isOk());

        Mockito.verify(mailVerifyService).verifyEmail(email, certificationNumber);
    }

    @Test
    @DisplayName("요청한 이메일이 존재하지 않아 임시번호 전송에 실패한다.")
    public void verify_CertificationNumber_Fail_Email_Not_Exists() throws Exception {
        doThrow(new EmailNotFoundException()).when(mailVerifyService).verifyEmail(email, certificationNumber);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/mails/verify")
                .param("email", "test@example.com")
                .param("certificationNumber", "123456"));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("FAIL"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.message").value("이메일이 존재하지 않습니다."));
    }

    @Test
    @DisplayName("메일로 전송 했던 임시번호와 입력한 임시번호가 일치하지 않아 검증에 실패한다.")
    public void verify_CertificationNumber_Fail_Invalid_Certificated_Number() throws Exception {
        doThrow(new InvalidCertificationNumberException()).when(mailVerifyService).verifyEmail(email, certificationNumber);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/mails/verify")
                .param("email", "test@example.com")
                .param("certificationNumber", "123456"));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("FAIL"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.message").value("인증 번호가 다릅니다."));
    }
}