package com.jinddung2.couponlab.coupon.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jinddung2.couponlab.coupon.model.IssueResult;
import com.jinddung2.couponlab.coupon.service.CouponIssueService;

@WebMvcTest(CouponController.class)
class CouponControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CouponIssueService couponIssueService;

    @Test
    void issuedIsMappedTo200WithSeq() throws Exception {
        given(couponIssueService.issue(7L, 42L)).willReturn(new IssueResult.Issued(3));

        mockMvc.perform(post("/coupon-events/7/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andExpect(jsonPath("$.seq").value(3));
    }

    @Test
    void soldOutIsMappedTo409WithoutSeq() throws Exception {
        given(couponIssueService.issue(7L, 42L)).willReturn(new IssueResult.SoldOut());

        mockMvc.perform(post("/coupon-events/7/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":42}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.result").value("SOLD_OUT"))
                .andExpect(jsonPath("$.seq").doesNotExist());
    }
}
