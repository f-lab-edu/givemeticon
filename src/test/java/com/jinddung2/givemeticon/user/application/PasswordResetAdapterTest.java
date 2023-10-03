package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.mail.application.MailSendService;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetAdapterTest {

    @InjectMocks
    PasswordResetAdapter passwordResetAdapter;
    @Mock
    MailSendService mailSendService;
    @Mock
    UserService userService;

    String email;
    String tempPassword;

    @BeforeEach
    void setUp() {
        passwordResetAdapter = new PasswordResetAdapter(mailSendService, userService);
        email = "test1234@example.com";
        tempPassword = "AbCdEfGh";
    }

    @Test
    void resetPasswordAndSendEmail() throws MessagingException {
        when(mailSendService.sendEmailForTemporaryPassword(email)).thenReturn(tempPassword);
        doNothing().when(userService).resetPassword(email, tempPassword);

        passwordResetAdapter.resetPasswordAndSendEmail(email);
    }
}