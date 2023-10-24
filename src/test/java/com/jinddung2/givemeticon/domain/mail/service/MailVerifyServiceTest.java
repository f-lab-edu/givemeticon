package com.jinddung2.givemeticon.domain.mail.service;

import com.jinddung2.givemeticon.domain.mail.exception.EmailNotFoundException;
import com.jinddung2.givemeticon.domain.mail.exception.InvalidCertificationNumberException;
import com.jinddung2.givemeticon.domain.mail.repository.CertificationNumberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailVerifyServiceTest {
    @InjectMocks
    MailVerifyService mailVerifyService;

    @Mock
    CertificationNumberDao certificationNumberDao;

    String email;
    String certificationNumber;
    String invalidCertificationNumber;

    @BeforeEach
    void setUp() {
        email = "test@example.com";
        certificationNumber = "123456";
        invalidCertificationNumber = "999999";
    }

    @Test
    @DisplayName("유효한 인증번호 검증")
    void verifyEmail_ValidCertification() {

        when(certificationNumberDao.hasKey(email)).thenReturn(true);
        when(certificationNumberDao.getCertificationNumber(email)).thenReturn(certificationNumber);

        assertDoesNotThrow(() -> mailVerifyService.verifyEmail(email, certificationNumber));

        verify(certificationNumberDao).removeCertificationNumber(email);
    }

    @Test
    @DisplayName("유효하지 않는 인증번호 테스트")
    void verifyEmail_InvalidCertification() {

        when(certificationNumberDao.hasKey(email)).thenReturn(true);
        when(certificationNumberDao.getCertificationNumber(email)).thenReturn(certificationNumber);

        assertThrows(InvalidCertificationNumberException.class, () -> mailVerifyService.verifyEmail(email, invalidCertificationNumber));
    }

    @Test
    @DisplayName("해당 이메일 주소에 대한 인증번호가 없는 경우")
    void verifyEmail_CertificationNotFound() {

        when(certificationNumberDao.hasKey(email)).thenReturn(false);

        assertThrows(EmailNotFoundException.class, () -> mailVerifyService.verifyEmail(email, certificationNumber));
    }

}