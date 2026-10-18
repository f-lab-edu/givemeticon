package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponMqAdmissionResponse;
import com.jinddung2.givemeticon.domain.coupon.mq.CouponMqAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@Profile("coupon-mq")
@RequiredArgsConstructor
@RequestMapping("/api/v2/coupon-events/{eventId}/applications")
public class CouponMqAdmissionController {
    private final CouponMqAdmissionService service;
    @PostMapping(consumes = "application/json")
    public ResponseEntity<CouponMqAdmissionResponse> accept(@SessionAttribute(name = LOGIN_USER) int memberId,
            @PathVariable long eventId, @RequestBody byte[] body) {
        return service.accept(eventId, memberId, body);
    }
    @GetMapping("/attempts/{attemptId}")
    public CouponMqAdmissionResponse query(@SessionAttribute(name = LOGIN_USER) int memberId,
            @PathVariable long eventId, @PathVariable String attemptId) {
        return service.query(eventId, memberId, attemptId);
    }
}
