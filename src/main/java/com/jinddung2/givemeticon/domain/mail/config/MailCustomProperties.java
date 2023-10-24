package com.jinddung2.givemeticon.domain.mail.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Component
@ConfigurationProperties(prefix = "mail")
@RequiredArgsConstructor
public class MailCustomProperties {
    @Value("${mail.title.certification}")
    private String mailTitleCertification;
    @Value("${mail.domain.name}")
    private String domainName;
}
