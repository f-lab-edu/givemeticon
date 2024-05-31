package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.UserFixture;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetFacadeTest {

    @InjectMocks
    PasswordResetFacade sut;
    @Mock
    MailSendService mailSendService;
    @Mock
    UserService userService;

    @Test
    void resetPasswordAndSendEmail() throws MessagingException {
        User userFixture = UserFixture.createUserFixture();
        String email = userFixture.getEmail();
        String tempPassword = "AbCdEfGh";
        when(mailSendService.sendEmailForTemporaryPassword(email)).thenReturn(tempPassword);
        doNothing().when(userService).resetPassword(email, tempPassword);

        sut.resetPasswordAndSendEmail(email);

        verify(mailSendService, times(1)).sendEmailForTemporaryPassword(email);
        verify(userService, times(1)).resetPassword(email, tempPassword);
    }
}