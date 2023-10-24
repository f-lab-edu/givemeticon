package com.jinddung2.givemeticon.domain.user.presentation.facade;

import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordResetFacade {

    private final MailSendService mailSendService;
    private final UserService userService;

    @Async(value = "mailExecutor")
    public void resetPasswordAndSendEmail(String email) throws MessagingException {
        String tempPassword = mailSendService.sendEmailForTemporaryPassword(email);
        userService.resetPassword(email, tempPassword);
    }
}
