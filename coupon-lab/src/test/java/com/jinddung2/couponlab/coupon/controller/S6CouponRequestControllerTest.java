package com.jinddung2.couponlab.coupon.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jinddung2.couponlab.coupon.model.IssueResult;
import com.jinddung2.couponlab.coupon.model.S6RequestRecord;
import com.jinddung2.couponlab.coupon.service.CouponIssueService;
import com.jinddung2.couponlab.coupon.service.S6PublishUnconfirmedException;
import com.jinddung2.couponlab.coupon.service.S6RequestProcessor;

/** S6's HTTP contract: 202 + requestId, 503 + requestId + retryable, and the result lookup (PENDING / final / 503). */
@WebMvcTest(controllers = {CouponController.class, S6CouponRequestController.class}, properties = "coupon.strategy=s6")
class S6CouponRequestControllerTest {

    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CouponIssueService couponIssueService;

    @MockBean
    private S6RequestProcessor processor;

    @Test
    void acceptedIsMappedTo202WithTheRequestId() throws Exception {
        given(couponIssueService.issue(7L, 42L, ID)).willReturn(new IssueResult.Accepted(ID));

        mockMvc.perform(post("/coupon-events/7/issue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":42,\"requestId\":\"" + ID + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.requestId").value(ID));
    }

    @Test
    void unconfirmedPublishIs503WithTheRequestIdAndRetryable() throws Exception {
        given(couponIssueService.issue(7L, 42L)).willThrow(new S6PublishUnconfirmedException(ID, "timeout", null));

        mockMvc.perform(post("/coupon-events/7/issue").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":42}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.requestId").value(ID))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void aRequestWithoutARowIsPending() throws Exception {
        given(processor.find(ID)).willReturn(null);

        mockMvc.perform(get("/coupon-requests/" + ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.seq").doesNotExist());
    }

    @Test
    void aDecidedRequestShowsItsFinalStatusAndSeq() throws Exception {
        given(processor.find(ID)).willReturn(new S6RequestRecord(ID, 7L, 42L, "ISSUED", 3, null));

        mockMvc.perform(get("/coupon-requests/" + ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED")).andExpect(jsonPath("$.seq").value(3));
    }

    @Test
    void theLookupIs503WhileTheDatabaseIsUnreachable() throws Exception {
        given(processor.find(ID)).willThrow(new DataAccessResourceFailureException("db down",
                new java.sql.SQLTransientConnectionException("Connection is not available")));

        mockMvc.perform(get("/coupon-requests/" + ID)).andExpect(status().isServiceUnavailable());
    }
}
