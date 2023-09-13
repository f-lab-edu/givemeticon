package com.jinddung2.givemeticon.mail.application;

import com.jinddung2.givemeticon.mail.exception.InvalidCertificationNumberException;
import com.jinddung2.givemeticon.mail.infrastructure.CertificationNumberDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailVerifyService {

    private final CertificationNumberDao certificationNumberDao;

    public void verifyEmail(String email, String certificationNumber) {
        if (!isVerify(email, certificationNumber)) {
            throw new InvalidCertificationNumberException();
        }
        certificationNumberDao.removeCertificationNumber(email);
    }

    private boolean isVerify(String email, String certificationNumber) {
        return (certificationNumberDao.hasKey(email) &&
                certificationNumberDao.getCertificationNumber(email)
                        .equals(certificationNumber));
    }
}
