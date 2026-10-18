package com.jinddung2.givemeticon.domain.coupon.mq;

import com.jinddung2.givemeticon.domain.coupon.domain.*;
import com.jinddung2.givemeticon.domain.coupon.mapper.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponMqReadinessTest {
    final CouponEventMapper events=mock(CouponEventMapper.class);
    final CouponMqControlMapper controls=mock(CouponMqControlMapper.class);
    final CouponMqReadinessTransactionService tx=new CouponMqReadinessTransactionService(events,controls);
    final CouponEvent event=CouponMqAdmissionTransactionServiceTest.event(CouponEventStatus.SCHEDULED);
    @Test void topologyAndListenerAreRequiredBeforeAnyDatabaseWork() {
        assertThrows(CouponMqReadinessTransactionService.NotReady.class,()->tx.initialize(7,false,true));
        assertThrows(CouponMqReadinessTransactionService.NotReady.class,()->tx.initialize(7,true,false));
        verifyNoInteractions(events,controls);
    }
    @Test void databaseTimePreventsEarlyOpen() {
        ReflectionTestUtils.setField(event,"dbNow",event.getStartsAtUtc().minusNanos(1000));
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        assertThrows(CouponMqReadinessTransactionService.NotReady.class,()->tx.initialize(7,true,true));
        verify(events,never()).markOpen(anyLong()); verify(controls,never()).insert(any());
    }
    @Test void locksThenOpensAndEstablishesProofInOneTransactionBoundary() {
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        when(controls.insert(any())).thenReturn(1); when(events.markOpen(7)).thenReturn(1);
        when(controls.establishOpen(eq(7L),anyString())).thenReturn(1);
        var result=tx.initialize(7,true,true); assertNotNull(UUID.fromString(result.token()));
        var order=inOrder(events,controls); order.verify(events).findByIdForUpdate(7);order.verify(controls).findForUpdate(7);
        order.verify(controls).insert(any());order.verify(events).markOpen(7);order.verify(controls).establishOpen(7,result.token());
    }
    @Test void tokenAndPoisonPauseSurviveRefreshAndClosedNeverReopens() {
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        String token=UUID.randomUUID().toString();
        when(controls.findForUpdate(7)).thenReturn(Optional.of(new CouponMqControl(7,CouponMqControl.version(event),token,LocalDateTime.now(),true)));
        ReflectionTestUtils.setField(event,"status",CouponEventStatus.OPEN);
        assertEquals(token,tx.initialize(7,true,true).token());
        ReflectionTestUtils.setField(event,"status",CouponEventStatus.CLOSED);
        assertTrue(tx.initialize(7,true,true).closed());
        verify(events,never()).markOpen(anyLong());verify(controls,never()).establishOpen(anyLong(),anyString());
        assertFalse(tx.healthy(7));
    }
    @Test void changedConfigurationFailsClosedWithoutRotation() {
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        when(controls.findForUpdate(7)).thenReturn(Optional.of(new CouponMqControl(7,"0".repeat(64),null,null,true)));
        assertThrows(CouponMqReadinessTransactionService.Drift.class,()->tx.initialize(7,true,true));
        verify(controls,never()).establishOpen(anyLong(),anyString());
    }
    @Test void freshClosedEventCannotMintProof() {
        ReflectionTestUtils.setField(event,"status",CouponEventStatus.CLOSED);
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        assertThrows(CouponMqReadinessTransactionService.NotReady.class,()->tx.initialize(7,true,true));
        verify(controls,never()).insert(any());
    }
    @Test void refreshFailureRetainsVerifiedSnapshotButKnownDriftBlocksIt() {
        var topology=mock(CouponMqConfiguration.class);var admin=mock(RabbitAdmin.class);var consumer=mock(CouponMqConsumer.class);
        var transaction=mock(CouponMqReadinessTransactionService.class);when(consumer.registered()).thenReturn(true);
        var snapshot=new CouponMqReadinessTransactionService.Snapshot(7,"a".repeat(64),UUID.randomUUID().toString(),false);
        when(transaction.initialize(7,true,true)).thenReturn(snapshot).thenThrow(new DataAccessResourceFailureException("offline"));
        var readiness=new CouponMqReadinessService(7,topology,admin,consumer,transaction);
        readiness.refresh();assertSame(snapshot,readiness.snapshot(7));
        readiness.refresh();assertSame(snapshot,readiness.snapshot(7));
        when(topology.verifyTopology(admin)).thenThrow(new CouponMqReadinessTransactionService.Drift());
        readiness.refresh();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->readiness.snapshot(7));
    }
    @Test void failedStartupHasNoTrustedSnapshot() {
        var topology=mock(CouponMqConfiguration.class);var transaction=mock(CouponMqReadinessTransactionService.class);
        var readiness=new CouponMqReadinessService(7,topology,mock(RabbitAdmin.class),mock(CouponMqConsumer.class),transaction);
        readiness.refresh();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->readiness.snapshot(7));
        verify(transaction,never()).initialize(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void refreshCannotSilentlyReplaceVerifiedToken() {
        var topology=mock(CouponMqConfiguration.class);var admin=mock(RabbitAdmin.class);var consumer=mock(CouponMqConsumer.class);
        var transaction=mock(CouponMqReadinessTransactionService.class);when(consumer.registered()).thenReturn(true);
        String version="a".repeat(64);
        when(transaction.initialize(7,true,true)).thenReturn(
            new CouponMqReadinessTransactionService.Snapshot(7,version,UUID.randomUUID().toString(),false),
            new CouponMqReadinessTransactionService.Snapshot(7,version,UUID.randomUUID().toString(),false));
        var readiness=new CouponMqReadinessService(7,topology,admin,consumer,transaction);
        readiness.refresh();assertNotNull(readiness.snapshot(7));readiness.refresh();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->readiness.snapshot(7));
    }

    @Test void openProofFailureRollsBackTheWholeProxiedReadinessTransaction() {
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));when(controls.insert(any())).thenReturn(1);
        when(events.markOpen(7)).thenReturn(1);when(controls.establishOpen(eq(7L),anyString())).thenReturn(0);
        var evidence=new ArrayList<String>();var manager=new CouponMqConsumerTest.Transactions(evidence);
        var factory=new org.springframework.aop.framework.ProxyFactory(tx);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy=(CouponMqReadinessTransactionService)factory.getProxy();
        assertThrows(IllegalStateException.class,()->proxy.initialize(7,true,true));
        assertEquals(List.of("begin","rollback"),evidence);
    }

}
