package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.mail.application.MailSendService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordResetAdapter {

    private final MailSendService mailSendService;
    private final UserService userService;

    @Async(value = "mailExecutor")
    public void resetPasswordAndSendEmail(String email) throws MessagingException {
        String tempPassword = mailSendService.sendEmailForTemporaryPassword(email);
        userService.resetPassword(email, tempPassword);
    }
}
