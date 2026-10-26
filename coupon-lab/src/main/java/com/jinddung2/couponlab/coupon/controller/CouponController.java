package com.jinddung2.couponlab.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jinddung2.couponlab.coupon.model.IssueResult;
import com.jinddung2.couponlab.coupon.service.CouponIssueService;

@RestController
public class CouponController {

    private final CouponIssueService couponIssueService;

    public CouponController(CouponIssueService couponIssueService) {
        this.couponIssueService = couponIssueService;
    }

    @PostMapping("/coupon-events/{eventId}/issue")
    public ResponseEntity<?> issue(@PathVariable long eventId, @RequestBody IssueRequest request) {
        // The two-argument call stays the path of every stage that has no request id.
        IssueResult result = request.requestId() == null
                ? couponIssueService.issue(eventId, request.memberId())
                : couponIssueService.issue(eventId, request.memberId(), request.requestId());
        return switch (result) {
            case IssueResult.Issued issued -> ResponseEntity.ok(new IssueResponse("ISSUED", issued.seq()));
            case IssueResult.SoldOut soldOut ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(new IssueResponse("SOLD_OUT", null));
            case IssueResult.AlreadyIssued alreadyIssued ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(new IssueResponse("ALREADY_ISSUED", null));
            case IssueResult.Accepted accepted ->
                    ResponseEntity.accepted().body(new AcceptedResponse("ACCEPTED", accepted.requestId()));
        };
    }

    /** requestId is optional and only S6 reads it. */
    public record IssueRequest(long memberId, String requestId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IssueResponse(String result, Integer seq) {
    }

    public record AcceptedResponse(String status, String requestId) {
    }
}
