package com.jinddung2.givemeticon.mail.application;

import com.jinddung2.givemeticon.mail.infrastructure.CertificationGenerator;
import com.jinddung2.givemeticon.mail.infrastructure.CertificationNumberDao;
import com.jinddung2.givemeticon.mail.presentation.response.EmailCertificationResponse;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.security.NoSuchAlgorithmException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailSendServiceTest {

    @InjectMocks
    MailSendService mailSendService;
    @Mock
    JavaMailSender mailSender;

    @Mock
    CertificationNumberDao certificationNumberDao;

    @Mock
    CertificationGenerator generator;

    @BeforeEach
    void setUp() {
        mailSendService = new MailSendService(mailSender, certificationNumberDao, generator);
    }

    @Test
    @DisplayName("이메일 보내기")
    void sendEmailForCertification() throws NoSuchAlgorithmException, MessagingException {
        // given
        String email = "test@example.com";
        String certificationNumber = "123456";

        MimeMessage mimeMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(generator.createCertificationNumber()).thenReturn(certificationNumber);

        // when
        EmailCertificationResponse response = mailSendService.sendEmailForCertification(email);

        verify(mailSender).send(any(MimeMessage.class));
        verify(certificationNumberDao).saveCertificationNumber(email, certificationNumber);

        assertEquals(email, response.getEmail());
        assertEquals(certificationNumber, response.getCertificationNumber());
    }

}