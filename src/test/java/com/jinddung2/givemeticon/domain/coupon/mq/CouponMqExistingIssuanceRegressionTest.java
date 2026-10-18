package com.jinddung2.givemeticon.domain.coupon.mq;

import com.jinddung2.givemeticon.domain.coupon.domain.*;
import com.jinddung2.givemeticon.domain.coupon.mapper.*;
import com.jinddung2.givemeticon.domain.coupon.service.*;
import com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponAdmissionTransactionTimers;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CouponMqExistingIssuanceRegressionTest {
    final CouponEventMapper events=mock(CouponEventMapper.class);
    final CouponApplicationMapper applications=mock(CouponApplicationMapper.class);
    final CouponAwardMapper awards=mock(CouponAwardMapper.class);
    @Test void unchangedV1StillRejectsNewClosedAdmissions() {
        var event=CouponMqAdmissionTransactionServiceTest.event(CouponEventStatus.CLOSED);
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        var service=new CouponBatchAdmissionTransactionService(events,applications,mock(CouponAdmissionTransactionTimers.class),mock(CouponEventClosureCache.class));
        var result=service.acceptBatch(7,List.of(3));
        verify(applications,never()).insertBatch(anyList());verify(events,never()).updateNextAcceptanceSequence(anyLong(),anyLong());
        assertNotNull(result);
    }
    @Test void unchangedWorkerDrainsClosedExcessPendingToSoldOutWithoutAwardOrStock() {
        var event=CouponMqAdmissionTransactionServiceTest.event(CouponEventStatus.CLOSED);
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        var application=CouponApplication.pending(7,3,101);ReflectionTestUtils.setField(application,"id",11L);
        when(applications.findFirstPendingForUpdate(7)).thenReturn(Optional.of(application));
        when(applications.markSoldOut(eq(11L),anyString())).thenReturn(1);
        assertTrue(new CouponEventIssuanceTransactionService(events,applications,awards).processNext(7));
        verify(applications).markSoldOut(11,"SOLD_OUT_SEQUENCE_EXCEEDS_TOTAL_QUANTITY");verifyNoInteractions(awards);
        verify(events,never()).incrementIssuedQuantityAndMaybeClose(anyLong());
    }
    @Test void unchangedBatchWorkerKeepsHighNormalStockAndSoldOutBoundaries() {
        var event=CouponMqAdmissionTransactionServiceTest.event(CouponEventStatus.OPEN);
        when(events.findByIdForUpdate(7)).thenReturn(Optional.of(event));
        var high=CouponApplication.pending(7,3,50);var normal=CouponApplication.pending(7,4,51);var excess=CouponApplication.pending(7,5,101);
        ReflectionTestUtils.setField(high,"id",11L);ReflectionTestUtils.setField(normal,"id",12L);ReflectionTestUtils.setField(excess,"id",13L);
        when(applications.findPendingForUpdate(7,50)).thenReturn(List.of(high,normal,excess));
        when(applications.markSoldOutBatch(eq(List.of(13L)),anyString())).thenReturn(1);when(awards.insertBatch(anyList())).thenReturn(2);
        when(events.incrementIssuedQuantityByAndMaybeClose(7,2)).thenReturn(1);when(applications.markIssuedBatch(List.of(11L,12L))).thenReturn(2);
        var worker=new CouponEventIssuanceBatchTransactionService(events,applications,awards);ReflectionTestUtils.setField(worker,"batchSize",50);
        assertTrue(worker.processNext(7));
        verify(awards).insertBatch(argThat(a->a.size()==2 && a.get(0).getTier()==CouponTier.HIGH && a.get(0).getPoints()==1000
            && a.get(1).getTier()==CouponTier.NORMAL && a.get(1).getPoints()==100));
    }
}
