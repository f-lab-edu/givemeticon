package com.jinddung2.givemeticon.mail.application;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.common.utils.PasswordGenerator;
import com.jinddung2.givemeticon.mail.infrastructure.CertificationNumberDao;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.NoSuchAlgorithmException;

@Service
@RequiredArgsConstructor
public class MailSendService {

    private final JavaMailSender mailSender;
    private final CertificationNumberDao certificationNumberDao;
    private final PasswordGenerator passwordGenerator;
    private final CertificationGenerator certificationGenerator;
    private final MailCustomProperties properties;

    @Async(value = "mailExecutor")
    public void sendEmailForCertification(String email) throws NoSuchAlgorithmException, MessagingException {
        String certificationNumber = certificationGenerator.createCertificationNumber();
        String content = String.format("%s/api/v1/users/verify?certificationNumber=%s&email=%s   링크를 3분 이내에 클릭해주세요.", properties.getDomainName(), certificationNumber, email);
        certificationNumberDao.saveCertificationNumber(email, certificationNumber);
        sendMail(email, content);
    }

    public String sendEmailForTemporaryPassword(String email) throws MessagingException {
        String temporaryPassword = passwordGenerator.createTemporaryPassword();
        String content = String.format("%s 임시 비밀번호 안내 관련 메일입니다. 회원님의 임시 비밀번호는 %s 입니다. 로그인 후 비밀번호를 변경해 주세요."
                , properties.getDomainName(), temporaryPassword);
        sendMail(email, content);
        return temporaryPassword;
    }

    private void sendMail(String email, String content) throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage);
        helper.setTo(email);
        helper.setSubject(properties.getMailTitleCertification());
        helper.setText(content);
        mailSender.send(mimeMessage);
    }
}
