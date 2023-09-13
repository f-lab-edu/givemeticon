package com.jinddung2.givemeticon.mail.presentation;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.mail.application.MailSendService;
import com.jinddung2.givemeticon.mail.presentation.request.EmailCertificationRequest;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.NoSuchAlgorithmException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MailController {

    private final MailSendService mailSendService;

    @PostMapping("/send-certification")
    public ResponseEntity<ApiResponse<Void>> sendCertificationNumber(@Validated @RequestBody EmailCertificationRequest request)
            throws MessagingException, NoSuchAlgorithmException {
        mailSendService.sendEmailForCertification(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
