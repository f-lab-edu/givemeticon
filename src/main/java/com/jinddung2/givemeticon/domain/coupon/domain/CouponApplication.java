package com.jinddung2.givemeticon.domain.coupon.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class CouponApplication {
    private long id;
    private String publicRequestId;
    private long eventId;
    private int memberId;
    private long acceptanceSequence;
    private CouponApplicationStatus status;
    private LocalDateTime acceptedAt;
    private LocalDateTime finalizedAt;
    private String failureReason;

    @Builder
    private CouponApplication(String publicRequestId, long eventId, int memberId, long acceptanceSequence,
                              CouponApplicationStatus status) {
        this.publicRequestId = publicRequestId;
        this.eventId = eventId;
        this.memberId = memberId;
        this.acceptanceSequence = acceptanceSequence;
        this.status = status;
    }

    public static CouponApplication pending(long eventId, int memberId, long acceptanceSequence) {
        return CouponApplication.builder()
                .publicRequestId(UUID.randomUUID().toString())
                .eventId(eventId)
                .memberId(memberId)
                .acceptanceSequence(acceptanceSequence)
                .status(CouponApplicationStatus.PENDING)
                .build();
    }
}
