package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.user.service.SessionLoginService;
import com.jinddung2.givemeticon.domain.coupon.mq.*;
import com.jinddung2.givemeticon.domain.coupon.mapper.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CouponMqAdmissionController.class)
@ActiveProfiles("coupon-mq")
@Import({CouponMqAdmissionService.class, WebConfig.class, AuthInterceptor.class, SessionLoginService.class})
class CouponMqAdmissionControllerTest {
    @Autowired MockMvc mvc;
    @MockBean CouponMqReadinessService readiness;
    @MockBean CouponMqPublisher publisher;
    @MockBean CouponMqAttemptMapper attempts;
    @MockBean CouponApplicationMapper applications;
    @MockBean CouponAwardMapper awards;
    MockHttpSession session;
    final String attempt="11111111-1111-4111-8111-111111111111", version="a".repeat(64),token="22222222-2222-4222-8222-222222222222";
    final String url="/api/v2/coupon-events/7/applications";
    String body(){return "{\"attemptId\":\""+attempt+"\"}";}
    @BeforeEach void setup(){session=new MockHttpSession();session.setAttribute(LOGIN_USER,3);
        when(readiness.snapshot(7)).thenReturn(new CouponMqReadinessTransactionService.Snapshot(7,version,token,false));}
    @Test void missingSessionUsesExistingAuthenticationRejection() throws Exception {
        mvc.perform(post(url).contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(publisher,attempts);
    }
    @Test void trustedSessionAndServerProvenanceReachSamePublisher() throws Exception {
        when(publisher.publish(any(),eq(7L),eq(3),eq("coupon.mq.7"),eq("admit"))).thenReturn(
            new CouponMqPublisher.Result(CouponMqPublisher.Status.ACCEPTED,attempt,"transport-only",CouponMqPublisher.Reason.CONFIRMED));
        mvc.perform(post(url).session(session).contentType("application/json").content(body()))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.attemptId").value(attempt)).andExpect(jsonPath("$.requestId").isEmpty());
        var bytes=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(publisher).publish(bytes.capture(),eq(7L),eq(3),eq("coupon.mq.7"),eq("admit"));
        String canonical=new String(bytes.getValue(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(canonical.contains("\"memberId\":3"));assertTrue(canonical.contains(version));assertTrue(canonical.contains(token));
        verifyNoInteractions(attempts,applications,awards);
    }
    @Test void forgedServerFieldsAndDuplicateKeysAreRejectedBeforePublishing() throws Exception {
        for(String extra:List.of("memberId","configVersion","openToken","eventId")) {
            mvc.perform(post(url).session(session).contentType("application/json").content(body().replace("}",",\""+extra+"\":null}")))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post(url).session(session).contentType("application/json").content("{\"attemptId\":\""+attempt+"\",\"attemptId\":\""+attempt+"\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }
    @Test void uncertainAndDefiniteTransportStatusesAreDistinct() throws Exception {
        when(publisher.publish(any(),anyLong(),anyInt(),anyString(),anyString())).thenReturn(
            new CouponMqPublisher.Result(CouponMqPublisher.Status.CHECKING,attempt,"c1",CouponMqPublisher.Reason.TIMEOUT),
            new CouponMqPublisher.Result(CouponMqPublisher.Status.REJECTED,attempt,"c2",CouponMqPublisher.Reason.RETURNED),
            new CouponMqPublisher.Result(CouponMqPublisher.Status.NOT_SENT,attempt,"c3",CouponMqPublisher.Reason.PRE_SEND_FAILURE));
        mvc.perform(post(url).session(session).contentType("application/json").content(body())).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CHECKING"));
        for(int i=0;i<2;i++)mvc.perform(post(url).session(session).contentType("application/json").content(body())).andExpect(status().isServiceUnavailable());
    }
    @Test void closedIsEndedWithoutPublishingOrResolvingPreviousUncertainty() throws Exception {
        when(readiness.snapshot(7)).thenReturn(new CouponMqReadinessTransactionService.Snapshot(7,version,token,true));
        mvc.perform(post(url).session(session).contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
        verifyNoInteractions(publisher,attempts);
    }
    @Test void queryScopesEventAndSessionMemberAndAbsentIsChecking() throws Exception {
        mvc.perform(get(url+"/attempts/"+attempt).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKING"));
        verify(attempts).find(7,3,attempt);session.setAttribute(LOGIN_USER,4);
        mvc.perform(get(url+"/attempts/"+attempt).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKING"));
        verify(attempts).find(7,4,attempt);verifyNoInteractions(applications,awards);
    }
    @Test void queryDatabaseFailureIs503NotAbsence() throws Exception {
        when(attempts.find(7,3,attempt)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        mvc.perform(get(url+"/attempts/"+attempt).session(session)).andExpect(status().isServiceUnavailable());
    }
    @Test void malformedAndOversizedRequestNeverTouchesDatabaseOrPublisher() throws Exception {
        for(String body:List.of("{}","{\"attemptId\":\"bad\"}"," ".repeat(4097)))
            mvc.perform(post(url).session(session).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(publisher,attempts,applications,awards);
    }
    @Test void durableRejectionAndMappedPendingRemainOwnerScoped() throws Exception {
        when(attempts.find(7,3,attempt)).thenReturn(Optional.of(new CouponMqAttempt(7,3,attempt,"a".repeat(64),version,null,
                "REJECTED",null,"OPEN_PROOF_MISSING",null)));
        mvc.perform(get(url+"/attempts/"+attempt).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.reason").value("OPEN_PROOF_MISSING"));
        verifyNoInteractions(applications,awards);
        when(attempts.find(7,3,attempt)).thenReturn(Optional.of(new CouponMqAttempt(7,3,attempt,"a".repeat(64),version,token,
                "MAPPED",3,null,null)));
        var application=com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication.pending(7,3,42);
        when(applications.findByEventIdAndMemberId(7,3)).thenReturn(Optional.of(application));
        mvc.perform(get(url+"/attempts/"+attempt).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.acceptanceSequence").value(42));
    }

}
