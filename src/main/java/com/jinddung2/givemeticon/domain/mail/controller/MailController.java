package com.jinddung2.givemeticon.domain.mail.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.mail.dto.request.EmailCertificationRequest;
import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
import com.jinddung2.givemeticon.domain.mail.service.MailVerifyService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.NoSuchAlgorithmException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/mails")
public class MailController {

    private final MailSendService mailSendService;
    private final MailVerifyService mailVerifyService;

    @PostMapping("/send-certification")
    public ResponseEntity<ApiResponse<Void>> sendCertificationNumber(@Validated @RequestBody EmailCertificationRequest request)
            throws MessagingException, NoSuchAlgorithmException {
        mailSendService.sendEmailForCertification(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verifyCertificationNumber(
            @RequestParam(name = "email") String email,
            @RequestParam(name = "certificationNumber") String certificationNumber
    ) {
        mailVerifyService.verifyEmail(email, certificationNumber);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
