package com.jinddung2.couponlab.coupon.controller;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jinddung2.couponlab.coupon.model.S6RequestRecord;
import com.jinddung2.couponlab.coupon.service.S6ErrorClassifier;
import com.jinddung2.couponlab.coupon.service.S6PublishUnconfirmedException;
import com.jinddung2.couponlab.coupon.service.S6RequestProcessor;

/**
 * S6 only: the result lookup, and the 503 body of an unconfirmed publish. A request id with no row is PENDING (accepted, not
 * decided yet, or never accepted: the lookup cannot tell them apart and no separate receipt store exists). While the
 * database is unreachable the lookup answers 503, never a guess.
 */
@RestController
@RestControllerAdvice(assignableTypes = {CouponController.class, S6CouponRequestController.class})
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")
public class S6CouponRequestController {

    private final S6RequestProcessor processor;

    public S6CouponRequestController(S6RequestProcessor processor) {
        this.processor = processor;
    }

    @GetMapping("/coupon-requests/{requestId}")
    public ResponseEntity<StatusResponse> status(@PathVariable String requestId) {
        S6RequestRecord record;
        try {
            record = processor.find(requestId);
        } catch (RuntimeException e) {
            if (S6ErrorClassifier.isDatabaseDown(e)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            throw e;
        }
        if (record == null) {
            return ResponseEntity.ok(new StatusResponse(requestId, "PENDING", null, null));
        }
        return ResponseEntity.ok(new StatusResponse(requestId, record.status(), record.seq(), record.reason()));
    }

    @ExceptionHandler(S6PublishUnconfirmedException.class)
    public ResponseEntity<Map<String, Object>> unconfirmed(S6PublishUnconfirmedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("requestId", e.requestId(), "retryable", true));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatusResponse(String requestId, String status, Integer seq, String reason) {
    }
}
