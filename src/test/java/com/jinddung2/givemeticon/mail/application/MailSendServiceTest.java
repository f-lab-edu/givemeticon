package com.jinddung2.givemeticon.mail.application;

import com.jinddung2.givemeticon.mail.infrastructure.CertificationGenerator;
import com.jinddung2.givemeticon.mail.infrastructure.CertificationNumberDao;
import com.jinddung2.givemeticon.mail.presentation.response.EmailCertificationResponse;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.test.context.TestPropertySource;

import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@TestPropertySource(locations = "classpath:application-secret.yml")
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

    @Mock
    MimeMessage mimeMessage;

    @Mock
    MimeMessageHelper mimeMessageHelper;

    @Mock
    MailCustomProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mailSendService = new MailSendService(mailSender, certificationNumberDao, generator, properties);
    }

    @Test
    @DisplayName("이메일 보내기")
    void sendEmailForCertification() throws NoSuchAlgorithmException, MessagingException {
        // given
        String email = "test@example.com";
        String certificationNumber = "123456";
        String mailTitleCertification = "givemeticon 인증번호 안내";

        when(generator.createCertificationNumber()).thenReturn(certificationNumber);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        doNothing().when(mailSender).send(any(MimeMessage.class));
        // when
        when(properties.getMailTitleCertification()).thenReturn(mailTitleCertification);
        EmailCertificationResponse response = mailSendService.sendEmailForCertification(email);

        verify(generator).createCertificationNumber();
        verify(mailSender).send(any(MimeMessage.class));
        verify(certificationNumberDao).saveCertificationNumber(email, certificationNumber);

        assertEquals(email, response.getEmail());
        assertEquals(certificationNumber, response.getCertificationNumber());
    }

}