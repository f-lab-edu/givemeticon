package com.jinddung2.givemeticon.domain.coupon.mq;

import com.rabbitmq.client.Channel;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponMqConsumerTest {
    final CouponMqAdmissionTransactionService admission=mock(CouponMqAdmissionTransactionService.class);
    final CouponMqReadinessTransactionService readiness=mock(CouponMqReadinessTransactionService.class);
    final Channel channel=mock(Channel.class);
    Message message(long tag) {var p=new MessageProperties();p.setDeliveryTag(tag);return new Message(new byte[]{1},p);}
    static class Consumer extends CouponMqConsumer {
        long time=TimeUnit.SECONDS.toNanos(100);final List<Long> waits=new ArrayList<>();
        Consumer(CouponMqAdmissionTransactionService admission,CouponMqReadinessTransactionService readiness) {super(7,admission,readiness);}
        @Override void pause(long millis) {waits.add(millis);}
        @Override long now() {return time;}
    }
    @Test void committedPrefixUsesIndividualAckThenParksPoisonOnly() throws Exception {
        var batch=List.of(message(1),message(2),message(3));
        when(admission.accept(7,batch)).thenReturn(new CouponMqAdmissionTransactionService.BatchResult(
                List.of(CouponMqAdmissionTransactionService.Action.ACK,CouponMqAdmissionTransactionService.Action.PARK),true));
        try(var consumer=new Consumer(admission,readiness)) {
            consumer.process(batch,channel);
            var order=inOrder(admission,channel);order.verify(admission).accept(7,batch);
            order.verify(channel).basicAck(1,false);order.verify(channel).basicNack(2,false,false);verifyNoMoreInteractions(channel);
        }
    }
    @Test void transientFailureRetriesSameBatchAndNeverAcksRolledBackWork() throws Exception {
        var batch=List.of(message(1));
        when(admission.accept(7,batch)).thenThrow(new TransientDataAccessResourceException("offline"));
        try(var consumer=new Consumer(admission,readiness)) {
            assertThrows(TransientDataAccessResourceException.class,()->consumer.process(batch,channel));
            assertEquals(List.of(250L,1000L,2000L,5000L),consumer.waits);verify(admission,times(5)).accept(7,batch);verifyNoInteractions(channel);
        }
    }
    @Test void successfulRetryAcksOnceOnlyAfterSuccess() throws Exception {
        var batch=List.of(message(1));
        when(admission.accept(7,batch)).thenThrow(new TransientDataAccessResourceException("offline"))
                .thenReturn(new CouponMqAdmissionTransactionService.BatchResult(List.of(CouponMqAdmissionTransactionService.Action.ACK),false));
        try(var consumer=new Consumer(admission,readiness)) {consumer.process(batch,channel);verify(channel).basicAck(1,false);}
    }
    @Test void databaseHealthResumeNeedsThreeChecksAndThirtySecondsAndNeverClearsSharedPause() {
        var container=mock(SimpleMessageListenerContainer.class);when(readiness.healthy(7)).thenReturn(true);
        try(var consumer=new Consumer(admission,readiness)) {
            consumer.attach(container);consumer.topologyVerified(true);consumer.supervise();verify(container).start();
            consumer.parkDetected();consumer.time+=TimeUnit.SECONDS.toNanos(5);consumer.supervise();
            consumer.time+=TimeUnit.SECONDS.toNanos(5);consumer.supervise();
            consumer.time+=TimeUnit.SECONDS.toNanos(5);consumer.supervise();verify(container,times(1)).start();
            when(readiness.healthy(7)).thenReturn(false);consumer.time+=TimeUnit.SECONDS.toNanos(15);consumer.supervise();
            when(readiness.healthy(7)).thenReturn(true);
            for(int i=0;i<3;i++){consumer.time+=TimeUnit.SECONDS.toNanos(5);consumer.supervise();}
            verify(container,times(2)).start();verify(readiness,never()).pause(anyLong());
        }
    }
    @Test void noStartBeforeVerifiedTopologyOrOnDatabaseFailure() {
        var container=mock(SimpleMessageListenerContainer.class);
        try(var consumer=new Consumer(admission,readiness)) {
            consumer.attach(container);consumer.supervise();verify(container,never()).start();
            consumer.topologyVerified(true);when(readiness.healthy(7)).thenThrow(new DataAccessResourceFailureException("offline"));
            consumer.supervise();verify(container,never()).start();
        }
    }
    static class Transactions extends AbstractPlatformTransactionManager {
        final List<String> evidence;boolean failCommit;
        Transactions(List<String> evidence){this.evidence=evidence;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){evidence.add("begin");}
        protected void doCommit(DefaultTransactionStatus status){evidence.add("commit");if(failCommit)throw new TransactionSystemException("commit failed");}
        protected void doRollback(DefaultTransactionStatus status){evidence.add("rollback");}
    }
    @Test void actualSpringProxyCommitPrecedesAckAndCommitFailureAcksZero() throws Exception {
        List<String> evidence=new ArrayList<>();var manager=new Transactions(evidence);
        var proxyFactory=new ProxyFactory(admission);proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var proxy=(CouponMqAdmissionTransactionService)proxyFactory.getProxy();var batch=List.of(message(1));
        when(admission.accept(7,batch)).thenReturn(new CouponMqAdmissionTransactionService.BatchResult(List.of(CouponMqAdmissionTransactionService.Action.ACK),false));
        doAnswer(invocation->{evidence.add("ack");return null;}).when(channel).basicAck(1,false);
        try(var consumer=new Consumer(proxy,readiness)) {
            consumer.process(batch,channel);assertEquals(List.of("begin","commit","ack"),evidence);
            clearInvocations(channel);evidence.clear();manager.failCommit=true;
            assertThrows(TransactionSystemException.class,()->consumer.process(batch,channel));verifyNoInteractions(channel);
        }
    }
    @Test void springProxyRollsBackAndAcksZeroOnMidBatchDatabaseFailure() throws Exception {
        var evidence=new ArrayList<String>();var manager=new Transactions(evidence);var factory=new ProxyFactory(admission);
        factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        when(admission.accept(anyLong(),anyList())).thenThrow(new DataIntegrityViolationException("injected second insert failure"));
        try(var consumer=new Consumer((CouponMqAdmissionTransactionService)factory.getProxy(),readiness)) {
            assertThrows(DataIntegrityViolationException.class,()->consumer.process(List.of(message(1),message(2)),channel));
            assertEquals(List.of("begin","rollback"),evidence);verifyNoInteractions(channel);
        }
    }
    @Test void exhaustedBatchStopsLaterCallbacksWithoutAckOrHotRequeue() {
        var container=mock(SimpleMessageListenerContainer.class);when(readiness.healthy(7)).thenReturn(true);
        when(admission.accept(anyLong(),anyList())).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("offline"));
        try(var consumer=new Consumer(admission,readiness)) {
            consumer.attach(container);consumer.topologyVerified(true);consumer.supervise();
            consumer.onMessage(message(1),channel);consumer.flush();consumer.onMessage(message(2),channel);consumer.flush();
            verify(admission,times(5)).accept(anyLong(),anyList());verifyNoInteractions(channel);
            assertEquals(List.of(250L,1000L,2000L,5000L),consumer.waits);
        }
    }
    @Test void unclassifiedRollbackPausesDurablyBeforeParkingAndStopsSuccessors() throws Exception {
        var container=mock(SimpleMessageListenerContainer.class);when(readiness.healthy(7)).thenReturn(true);
        when(admission.accept(anyLong(),anyList())).thenThrow(new DataIntegrityViolationException("unclassified"));
        try(var consumer=new Consumer(admission,readiness)) {
            consumer.attach(container);consumer.topologyVerified(true);consumer.supervise();
            consumer.onMessage(message(1),channel);consumer.flush();consumer.onMessage(message(2),channel);
            var order=inOrder(admission,readiness,channel);order.verify(admission).accept(anyLong(),anyList());
            order.verify(readiness).pause(7);order.verify(channel).basicNack(1,false,false);
            verify(channel,never()).basicAck(anyLong(),anyBoolean());verify(admission,times(1)).accept(anyLong(),anyList());
        }
    }

}
