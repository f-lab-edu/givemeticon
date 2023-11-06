package com.jinddung2.givemeticon.domain.user.controller.facade;

import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
import com.jinddung2.givemeticon.domain.user.presentation.facade.PasswordResetFacade;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetFacadeTest {

    @InjectMocks
    PasswordResetFacade passwordResetFacade;
    @Mock
    MailSendService mailSendService;
    @Mock
    UserService userService;

    String email;
    String tempPassword;

    @BeforeEach
    void setUp() {
        passwordResetFacade = new PasswordResetFacade(mailSendService, userService);
        email = "test1234@example.com";
        tempPassword = "AbCdEfGh";
    }

    @Test
    @DisplayName("현재 비밀번호를 임시비밀번호로 바꾸고 유저 메일에 임시비밀번호 발급에 성공한다.")
    void resetPasswordAndSendEmail() throws MessagingException {
        when(mailSendService.sendEmailForTemporaryPassword(email)).thenReturn(tempPassword);
        doNothing().when(userService).resetPassword(email, tempPassword);

        passwordResetFacade.resetPasswordAndSendEmail(email);
    }
}