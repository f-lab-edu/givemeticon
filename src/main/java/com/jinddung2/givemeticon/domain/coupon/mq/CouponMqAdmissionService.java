package com.jinddung2.givemeticon.domain.coupon.mq;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponMqAdmissionRequest;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponMqAdmissionResponse;
import com.jinddung2.givemeticon.domain.coupon.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("coupon-mq")
@RequiredArgsConstructor
public class CouponMqAdmissionService {
    private final CouponMqReadinessService readiness;
    private final CouponMqPublisher publisher;
    private final CouponMqAttemptMapper attempts;
    private final CouponApplicationMapper applications;
    private final CouponAwardMapper awards;

    public ResponseEntity<CouponMqAdmissionResponse> accept(long eventId, int memberId, byte[] request) {
        String attemptId = CouponMqAdmissionRequest.parse(request).attemptId();
        var snapshot = readiness.snapshot(eventId);
        if (snapshot.closed()) return ResponseEntity.ok(CouponMqAdmissionResponse.pending(eventId, attemptId,
                "ENDED", "Query the same attempt for any earlier uncertain publication"));
        byte[] body = CouponMqProtocol.payload(eventId, memberId, attemptId, snapshot.version(), snapshot.token());
        var result = publisher.publish(body, eventId, memberId, CouponMqConfiguration.exchange(eventId), "admit");
        var status = switch (result.status()) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case CHECKING -> HttpStatus.ACCEPTED;
            case NOT_SENT, REJECTED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String outcome = switch (result.status()) {
            case ACCEPTED -> "ACCEPTED";
            case CHECKING -> "CHECKING";
            case NOT_SENT, REJECTED -> null;
        };
        return ResponseEntity.status(status).body(CouponMqAdmissionResponse.pending(eventId, attemptId, outcome, result.reason().name()));
    }
    public CouponMqAdmissionResponse query(long eventId, int memberId, String requestedAttempt) {
        String attemptId = CouponMqAdmissionRequest.normalize(requestedAttempt);
        try {
            var attempt = attempts.find(eventId, memberId, attemptId);
            if (attempt.isEmpty()) return CouponMqAdmissionResponse.pending(eventId, attemptId, "CHECKING", null);
            if ("REJECTED".equals(attempt.get().getOutcome()))
                return CouponMqAdmissionResponse.pending(eventId, attemptId, "REJECTED", attempt.get().getRejectionReason());
            var application = applications.findByEventIdAndMemberId(eventId, memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "attempt mapping unavailable"));
            var award = application.getStatus() == com.jinddung2.givemeticon.domain.coupon.domain.CouponApplicationStatus.ISSUED
                    ? awards.findByApplicationId(application.getId()).orElse(null) : null;
            return new CouponMqAdmissionResponse(eventId, attemptId, application.getStatus().name(), application.getFailureReason(),
                    application.getPublicRequestId(), application.getAcceptanceSequence(),
                    award == null ? null : award.getTier().name(), award == null ? null : award.getPoints(),
                    award == null ? null : award.getStatus().name(),
                    "/api/v2/coupon-events/" + eventId + "/applications/attempts/" + attemptId);
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "attempt query unavailable; retry the same handle");
        }
    }
}
