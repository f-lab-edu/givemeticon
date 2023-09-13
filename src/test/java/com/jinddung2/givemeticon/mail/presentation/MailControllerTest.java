package com.jinddung2.givemeticon.mail.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.mail.application.MailSendService;
import com.jinddung2.givemeticon.mail.application.MailVerifyService;
import com.jinddung2.givemeticon.mail.presentation.response.EmailCertificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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

    String email;
    String certificationNumber;

    @BeforeEach
    void setUp() {
        email = "test@example.com";
        certificationNumber = "123456";
    }

    @Test
    public void testSendCertificationNumber() throws Exception {
        EmailCertificationResponse response = new EmailCertificationResponse(email, certificationNumber);
        Mockito.when(mailSendService.sendEmailForCertification(Mockito.anyString()))
                .thenReturn(response);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/mails/send-certification")
                        .content("{\"email\":\"test@example.com\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    @Test
    public void testVerifyCertificationNumber() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/mails/verify")
                        .param("email", "test@example.com")
                        .param("certificationNumber", "123456"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }
}