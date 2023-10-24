package com.jinddung2.givemeticon.mail.service;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.common.utils.PasswordGenerator;
import com.jinddung2.givemeticon.domain.mail.config.MailCustomProperties;
import com.jinddung2.givemeticon.domain.mail.repository.CertificationNumberDao;
import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
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

import java.security.NoSuchAlgorithmException;

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
    CertificationGenerator certificationGenerator;

    @Mock
    PasswordGenerator passwordGenerator;

    @Mock
    MimeMessage mimeMessage;

    @Mock
    MailCustomProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mailSendService = new MailSendService(mailSender, certificationNumberDao, passwordGenerator, certificationGenerator, properties);
    }

    @Test
    @DisplayName("회원가입 시 해당 이메일로 인증코드 6자리 숫자 보내기")
    void send_Email_Certification() throws NoSuchAlgorithmException, MessagingException {
        // given
        String email = "test@example.com";
        String certificationNumber = "123456";
        String mailTitleCertification = "givemeticon 인증번호 안내";

        when(certificationGenerator.createCertificationNumber()).thenReturn(certificationNumber);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        doNothing().when(mailSender).send(any(MimeMessage.class));
        // when
        when(properties.getMailTitleCertification()).thenReturn(mailTitleCertification);
        mailSendService.sendEmailForCertification(email);

        verify(certificationGenerator).createCertificationNumber();
        verify(mailSender).send(any(MimeMessage.class));
        verify(certificationNumberDao).saveCertificationNumber(email, certificationNumber);
    }

    @Test
    @DisplayName("회원가입 시 해당 이메일로 임시 비밀번호 12자리 숫자 보내기")
    void send_Email_Temp_Password() throws MessagingException {
        // given
        String email = "test@example.com";
        String tempPassword = "0123456789ab";
        String mailTitleCertification = "givemeticon 인증번호 안내";

        when(passwordGenerator.createTemporaryPassword()).thenReturn(tempPassword);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        doNothing().when(mailSender).send(any(MimeMessage.class));
        // when
        when(properties.getMailTitleCertification()).thenReturn(mailTitleCertification);
        String response = mailSendService.sendEmailForTemporaryPassword(email);

        verify(passwordGenerator).createTemporaryPassword();
        verify(mailSender).send(any(MimeMessage.class));

        assertEquals(tempPassword, response);
    }

}