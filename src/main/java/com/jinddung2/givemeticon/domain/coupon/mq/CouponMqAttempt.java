package com.jinddung2.givemeticon.domain.coupon.mq;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CouponMqAttempt {
    private long eventId;
    private int memberId;
    private String attemptId;
    private String payloadHash;
    private String configVersion;
    private String openToken;
    private String outcome;
    private Integer mappedMemberId;
    private String rejectionReason;
    private LocalDateTime createdAt;

    static CouponMqAttempt first(CouponMqProtocol.Payload payload, String hash) {
        boolean rejected = payload.openToken() == null;
        return new CouponMqAttempt(payload.eventId(), payload.memberId(), payload.attemptId(), hash,
                payload.configVersion(), payload.openToken(), rejected ? "REJECTED" : "MAPPED",
                rejected ? null : payload.memberId(), rejected ? "OPEN_PROOF_MISSING" : null, null);
    }
}
