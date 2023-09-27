package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.mail.application.MailSendService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordResetAdapter {

    private final MailSendService mailSendService;
    private final UserService userService;

    @Transactional
    public String resetPasswordAndSendEmail(String email) throws MessagingException {
        String tempPassword = mailSendService.sendEmailForTemporaryPassword(email);
        userService.resetPassword(email, tempPassword);
        return tempPassword;
    }

}
