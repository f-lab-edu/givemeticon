package com.jinddung2.givemeticon.domain.coupon.mq;

import com.jinddung2.givemeticon.domain.coupon.domain.*;
import com.jinddung2.givemeticon.domain.coupon.mapper.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponMqAdmissionTransactionServiceTest {
    final CouponEventMapper events = mock(CouponEventMapper.class);
    final CouponEventAdmissionCounterMapper counters = mock(CouponEventAdmissionCounterMapper.class);
    final CouponMqControlMapper controls = mock(CouponMqControlMapper.class);
    final CouponMqAttemptMapper attempts = mock(CouponMqAttemptMapper.class);
    final CouponApplicationMapper applications = mock(CouponApplicationMapper.class);
    final CouponMqAdmissionTransactionService service = new CouponMqAdmissionTransactionService(events, counters, controls, attempts, applications);
    final String token = UUID.randomUUID().toString(), attempt = UUID.randomUUID().toString();
    CouponEvent event;
    String version;
    @BeforeEach void setup() {
        event = event(CouponEventStatus.OPEN); version = CouponMqControl.version(event);
        when(events.findById(7)).thenReturn(Optional.of(event));
        when(counters.findByEventIdForUpdate(7)).thenReturn(Optional.of(0L));
        when(controls.findForUpdate(7)).thenReturn(Optional.of(new CouponMqControl(7, version, token, LocalDateTime.now(), false)));
        when(controls.pause(7)).thenReturn(1); when(applications.insert(any())).thenReturn(1); when(attempts.insert(any())).thenReturn(1);
        when(counters.updateNextAcceptanceSequence(eq(7L), anyLong())).thenReturn(1);
    }
    static CouponEvent event(CouponEventStatus status) {
        var e = new CouponEvent();
        Map<String,Object> fields = Map.of("id", 7L, "status", status, "publicId", "event-7", "totalQuantity", 100,
            "highQuantity", 50, "highPoints", 1000, "normalPoints", 100, "startsAtUtc", LocalDateTime.of(2026,10,13,0,0),
            "settingsLockedAt", LocalDateTime.of(2026,10,12,0,0), "dbNow", LocalDateTime.of(2026,10,18,0,0));
        fields.forEach((k,v)->ReflectionTestUtils.setField(e,k,v)); return e;
    }
    Message message(String token) {
        var p = new MessageProperties(); p.setConsumerQueue("coupon.mq.7.main");
        p.setReceivedExchange("coupon.mq.7"); p.setReceivedRoutingKey("admit");
        return new Message(CouponMqProtocol.payload(7, 3, attempt, version, token), p);
    }
    @Test void validClosedBacklogCreatesPendingAndMapped() {
        ReflectionTestUtils.setField(event,"status",CouponEventStatus.CLOSED);
        var result = service.accept(7, List.of(message(token)));
        assertEquals(List.of(CouponMqAdmissionTransactionService.Action.ACK), result.actions());
        verify(applications).insert(argThat(a->a.getAcceptanceSequence()==1 && a.getStatus()==CouponApplicationStatus.PENDING));
        verify(attempts).insert(argThat(a->a.getMappedMemberId()==3 && a.getOutcome().equals("MAPPED")));
        verify(events, never()).markOpen(anyLong());
    }
    @Test void explicitNullIsOnlyDurableRejectionAndDoesNotConsumeRank() {
        service.accept(7, List.of(message(null)));
        verify(attempts).insert(argThat(a->a.getOutcome().equals("REJECTED") && a.getMappedMemberId()==null
            && a.getOpenToken()==null && a.getRejectionReason().equals("OPEN_PROOF_MISSING")));
        verifyNoInteractions(applications); verify(counters, never()).updateNextAcceptanceSequence(anyLong(),anyLong());
    }
    @Test void duplicateSameHashReusesFirstOutcomeBeforeCurrentEligibility() {
        Message message = message(token); var payload = CouponMqProtocol.payload(message.getBody());
        when(attempts.find(7,3,attempt)).thenReturn(Optional.of(CouponMqAttempt.first(payload,CouponMqProtocol.sha256(message.getBody()))));
        ReflectionTestUtils.setField(event,"totalQuantity",101);
        assertFalse(service.accept(7,List.of(message)).paused());
        verify(attempts,never()).insert(any()); verifyNoInteractions(applications);
    }
    @Test void hashConflictPreservesFirstOutcomeAndPausesBeforeParking() {
        when(attempts.find(7,3,attempt)).thenReturn(Optional.of(CouponMqAttempt.first(CouponMqProtocol.payload(message(null).getBody()),"0".repeat(64))));
        var result=service.accept(7,List.of(message(token),message(token)));
        assertTrue(result.paused()); assertEquals(List.of(CouponMqAdmissionTransactionService.Action.PARK),result.actions());
        verify(controls).pause(7); verify(attempts,never()).insert(any()); verifyNoInteractions(applications);
    }
    @Test void sharedPauseBlocksEvenDuplicateProcessing() {
        when(controls.findForUpdate(7)).thenReturn(Optional.of(new CouponMqControl(7,version,token,LocalDateTime.now(),true)));
        assertThrows(CouponMqAdmissionTransactionService.Paused.class,()->service.accept(7,List.of(message(token))));
        verifyNoInteractions(attempts,applications);
    }
    @Test void wrongTokenOrConfigIsParkedWithoutRejectionOrRank() {
        var result=service.accept(7,List.of(message(UUID.randomUUID().toString())));
        assertTrue(result.paused()); verifyNoInteractions(applications); verify(attempts,never()).insert(any());
    }
    @Test void missingTokenFieldIsPoisonNotOpenProofMissing() {
        Message m=message(null); byte[] bytes=new String(m.getBody(),java.nio.charset.StandardCharsets.UTF_8)
            .replace(",\"openToken\":null", "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(service.accept(7,List.of(new Message(bytes,m.getMessageProperties()))).paused());
        verifyNoInteractions(applications,attempts);
    }
    @Test void existingApplicationGetsMappedWithoutNewRank() {
        when(applications.findByEventIdAndMemberId(7,3)).thenReturn(Optional.of(CouponApplication.pending(7,3,40)));
        service.accept(7,List.of(message(token)));
        verify(applications,never()).insert(any()); verify(counters,never()).updateNextAcceptanceSequence(anyLong(),anyLong());
        verify(attempts).insert(any());
    }
    @Test void lockOrderCounterThenControlWithoutLockingEvent() {
        service.accept(7,List.of(message(null))); var order=inOrder(counters,controls);
        order.verify(counters).findByEventIdForUpdate(7); order.verify(controls).findForUpdate(7);
        verify(events,never()).findByIdForUpdate(anyLong());
    }
    @Test void admissionAfterCloseDoesNotApplyV1TotalQuantityClosure() {
        ReflectionTestUtils.setField(event,"status",CouponEventStatus.CLOSED);
        when(counters.findByEventIdForUpdate(7)).thenReturn(Optional.of(100L)); // already at total_quantity (100)
        var result = service.accept(7, List.of(message(token)));
        assertEquals(List.of(CouponMqAdmissionTransactionService.Action.ACK), result.actions());
        verify(applications).insert(argThat(a->a.getAcceptanceSequence()==101 && a.getStatus()==CouponApplicationStatus.PENDING));
        verify(counters).updateNextAcceptanceSequence(7L,101L);
    }
    @Test void missingCounterRowIsAConfigurationError() {
        when(counters.findByEventIdForUpdate(7)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,()->service.accept(7,List.of(message(token))));
        verifyNoInteractions(attempts,applications);
    }
}
