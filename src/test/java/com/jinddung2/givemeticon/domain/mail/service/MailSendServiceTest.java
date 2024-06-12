package com.jinddung2.givemeticon.domain.mail.service;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.common.utils.PasswordGenerator;
import com.jinddung2.givemeticon.domain.mail.config.MailCustomProperties;
import com.jinddung2.givemeticon.domain.mail.repository.CertificationNumberDao;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailSendServiceTest {

    @InjectMocks
    MailSendService sut;

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
        sut.sendEmailForCertification(email);

        verify(certificationGenerator).createCertificationNumber();
        verify(mailSender).send(any(MimeMessage.class));
        verify(certificationNumberDao).saveCertificationNumber(email, certificationNumber);
    }

    @Test
    @DisplayName("회원가입 시 해당 이메일로 임시 비밀번호 12자리 숫자를 보낸다.")
    void sendEmailTempPassword_shouldSendTempPassword() throws MessagingException {

        String email = "test@example.com";
        String tempPassword = "0123456789ab";
        String mailTitleCertification = "givemeticon 인증번호 안내";

        when(passwordGenerator.createTemporaryPassword()).thenReturn(tempPassword);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));
        when(properties.getMailTitleCertification()).thenReturn(mailTitleCertification);

        String result = sut.sendEmailForTemporaryPassword(email);

        verify(passwordGenerator).createTemporaryPassword();
        verify(mailSender).send(any(MimeMessage.class));
        assertThat(result).isEqualTo(tempPassword);
    }
}