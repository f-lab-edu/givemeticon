package com.jinddung2.givemeticon.mail.application;

import com.jinddung2.givemeticon.mail.infrastructure.CertificationGenerator;
import com.jinddung2.givemeticon.mail.infrastructure.CertificationNumberDao;
import com.jinddung2.givemeticon.mail.presentation.response.EmailCertificationResponse;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.security.NoSuchAlgorithmException;

import static com.jinddung2.givemeticon.mail.constans.EmailConstants.DOMAIN_NAME;
import static com.jinddung2.givemeticon.mail.constans.EmailConstants.MAIL_TITLE_CERTIFICATION;

@Service
@RequiredArgsConstructor
public class MailSendService {

    private final JavaMailSender mailSender;
    private final CertificationNumberDao certificationNumberDao;
    private final CertificationGenerator generator;

    public EmailCertificationResponse sendEmailForCertification(String email) throws NoSuchAlgorithmException, MessagingException {

        String certificationNumber = generator.createCertificationNumber();

        String content = String.format("%s/api/v1/users/verify?certificationNumber=%s&email=%s   링크를 3분 이내에 클릭해주세요.", DOMAIN_NAME, certificationNumber, email);
        sendMail(email, content);
        certificationNumberDao.saveCertificationNumber(email, certificationNumber);
        return new EmailCertificationResponse(email, certificationNumber);
    }

    private void sendMail(String email, String content) throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage);
        helper.setTo(email);
        helper.setSubject(MAIL_TITLE_CERTIFICATION);
        helper.setText(content);
        mailSender.send(mimeMessage);
    }
}
