package com.jinddung2.givemeticon.domain.coupon.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.jinddung2.givemeticon.domain.coupon.mq.CouponMqPublisher.Status.*;

class CouponMqPublisherTest {
    RabbitTemplate template=mock(RabbitTemplate.class);
    CachingConnectionFactory factory=mock(CachingConnectionFactory.class);
    CouponMqPublisher publisher=new CouponMqPublisher(template,factory,Duration.ZERO);
    List<CorrelationData> sent=new ArrayList<>();
    AtomicReference<RabbitTemplate.ReturnsCallback> returned=new AtomicReference<>();
    byte[] body() throws Exception { return CouponMqProtocolTest.fixture("payload.json"); }
    void capture() {
        // Callback is installed in constructor; Mockito retains this real framework interface.
        var captor=org.mockito.ArgumentCaptor.forClass(RabbitTemplate.ReturnsCallback.class);
        verify(template).setReturnsCallback(captor.capture());returned.set(captor.getValue());
        doAnswer(inv->{sent.add(inv.getArgument(3));return null;}).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    ReturnedMessage returnFor(CorrelationData data) {
        var props=new org.springframework.amqp.core.MessageProperties();props.setMessageId(data.getId());
        return new ReturnedMessage(new Message(new byte[]{1},props),312,"NO_ROUTE","exchange","key");
    }
    @Test void ackOnlyPersistentMandatoryAndFactoryFlags() throws Exception {
        doAnswer(inv->{Message m=inv.getArgument(2);CorrelationData c=inv.getArgument(3);
            assertArrayEquals(body(),m.getBody());assertEquals(MessageDeliveryMode.PERSISTENT,m.getMessageProperties().getDeliveryMode());
            assertEquals(c.getId(),m.getMessageProperties().getMessageId());
            c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertEquals(ACCEPTED,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(0,publisher.pendingCount());
        verify(template).setMandatory(true);verify(factory).setPublisherReturns(true);verify(factory).setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    }
    @Test void nackAndReturnAckAreRejections() throws Exception {
        doAnswer(inv->{CorrelationData c=inv.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(false,"not logged"));return null;})
                .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());
        doAnswer(inv->{CorrelationData c=inv.getArgument(3);c.setReturned(returnFor(c));c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(0,publisher.pendingCount());
    }
    @Test void returnWithoutConfirmDoesNotFreeTracking() throws Exception {
        capture();doAnswer(inv->{CorrelationData c=inv.getArgument(3);sent.add(c);returned.get().returnedMessage(returnFor(c));return null;})
                .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(1,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(0,publisher.pendingCount());
    }
    @Test void timeoutLateAckAndDoubleCallbackPreserveCallerResult() throws Exception {
        capture();var result=publisher.publish(body(),7,91,"exchange","key");assertEquals(CHECKING,result.status());assertEquals(1,publisher.pendingCount());
        assertTrue(sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null)));
        assertFalse(sent.get(0).getFuture().complete(new CorrelationData.Confirm(false,"duplicate")));
        assertEquals(CHECKING,result.status());assertEquals(0,publisher.pendingCount());
    }
    @Test void lateReturnAndCorrelationIsolation() throws Exception {
        capture();var first=publisher.publish(body(),7,91,"exchange","key");var second=publisher.publish(body(),7,91,"exchange","key");
        assertEquals(first.attemptId(),second.attemptId());assertNotEquals(first.correlationId(),second.correlationId());
        returned.get().returnedMessage(returnFor(sent.get(0)));assertEquals(2,publisher.pendingCount());
        sent.get(1).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(1,publisher.pendingCount());
        sent.get(0).setReturned(returnFor(sent.get(0)));sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(0,publisher.pendingCount());
        returned.get().returnedMessage(returnFor(sent.get(0)));assertEquals(0,publisher.pendingCount());
        assertEquals(CHECKING,first.status());assertEquals(CHECKING,second.status());
    }
    @Test void invalidAndOversizeNeverSend() throws Exception {
        assertEquals(NOT_SENT,publisher.publish(CouponMqProtocolTest.fixture("null-token.json"),7,91,"exchange","key").status());
        assertEquals(NOT_SENT,publisher.publish(new byte[4097],7,91,"exchange","key").status());
        assertEquals(NOT_SENT,publisher.publish(body(),8,91,"exchange","key").status());
        verify(template,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));assertEquals(0,publisher.pendingCount());
    }
    @Test void unresolvedLimitIsGlobalAndOnlyConfirmReleases() throws Exception {
        capture();for(int i=0;i<100;i++) assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());
        String other=CouponMqProtocolTest.payload().replace("\"eventId\":7","\"eventId\":8");
        assertEquals(NOT_SENT,publisher.publish(other.getBytes(java.nio.charset.StandardCharsets.UTF_8),8,91,"exchange","other-key").status());
        assertEquals(100,sent.size());assertEquals(100,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(false,"nack"));
        assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(100,publisher.pendingCount());
        publisher.close();assertEquals(0,publisher.pendingCount());assertEquals(NOT_SENT,publisher.publish(body(),7,91,"exchange","key").status());
        publisher.close();verify(factory,times(1)).destroy();
    }
    @Test void sendExceptionMayHavePublishedAndKeepsSlot() throws Exception {
        capture();doAnswer(inv->{sent.add(inv.getArgument(3));throw new AmqpException("password=do-not-log");})
                .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var result=publisher.publish(body(),7,91,"exchange","key");assertEquals(CHECKING,result.status());assertEquals(1,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(CHECKING,result.status());assertEquals(0,publisher.pendingCount());
    }
    @Test void exceptionalFutureRetainsUnknownUntilClose() throws Exception {
        doAnswer(inv->{((CorrelationData)inv.getArgument(3)).getFuture().completeExceptionally(new IllegalStateException("token=secret"));return null;})
                .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(1,publisher.pendingCount());publisher.close();assertEquals(0,publisher.pendingCount());
    }
    @Test void shutdownDuringPublishReturnsCheckingAndLateAckIsHarmless() throws Exception {
        capture();doAnswer(inv->{sent.add(inv.getArgument(3));publisher.close();return null;})
                .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var result=publisher.publish(body(),7,91,"exchange","key");assertEquals(CHECKING,result.status());assertEquals(0,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(CHECKING,result.status());assertEquals(0,publisher.pendingCount());
    }
    @Test void multiplePublishersShareTheProcessBudget() throws Exception {
        capture();var secondTemplate=mock(RabbitTemplate.class);var secondFactory=mock(CachingConnectionFactory.class);
        var second=new CouponMqPublisher(secondTemplate,secondFactory,Duration.ZERO);
        try {
            for(int i=0;i<50;i++) {
                assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());
                assertEquals(CHECKING,second.publish(body(),7,91,"exchange","other").status());
            }
            assertEquals(NOT_SENT,publisher.publish(body(),7,91,"exchange","key").status());
            assertEquals(NOT_SENT,second.publish(body(),7,91,"exchange","other").status());
            verify(template,times(50)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
            verify(secondTemplate,times(50)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        } finally { publisher.close();second.close(); }
    }
    @Test void unknownSlotsStayOwnedUntilTransportDestructionReturns() throws Exception {
        capture();var otherTemplate=mock(RabbitTemplate.class);var otherFactory=mock(CachingConnectionFactory.class);
        var other=new CouponMqPublisher(otherTemplate,otherFactory,Duration.ZERO);
        try {
            for(int i=0;i<100;i++) publisher.publish(body(),7,91,"exchange","key");
            doAnswer(inv->{assertEquals(NOT_SENT,other.publish(body(),7,91,"exchange","other").status());return null;}).when(factory).destroy();
            publisher.close();
            assertEquals(CHECKING,other.publish(body(),7,91,"exchange","other").status());
        } finally { publisher.close();other.close(); }
    }
    @Test void concurrentUnresolvedSendsNeverExceedOneHundred() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv->{calls.incrementAndGet();return null;}).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        byte[] body=body();var executor=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var jobs=new ArrayList<java.util.concurrent.Callable<CouponMqPublisher.Status>>();
            for(int i=0;i<200;i++) jobs.add(()->publisher.publish(body,7,91,"exchange","key").status());
            var results=executor.invokeAll(jobs);long checking=0;
            for(var r:results) if(r.get()==CHECKING) checking++;
            assertEquals(100,checking);assertEquals(100,calls.get());assertEquals(100,publisher.pendingCount());
        } finally { executor.shutdownNow();publisher.close(); }
    }
    @Test void logsKeepOrderWithoutRawIdentityPayloadTokenOrException() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponMqPublisher.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            capture();var result=publisher.publish(body(),7,91,"exchange","key");
            returned.get().returnedMessage(returnFor(sent.get(0)));sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,"password=do-not-log"));
            doThrow(new AmqpException("token=do-not-log")).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
            publisher.publish(body(),7,91,"exchange","key");
            String log=events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(log.indexOf("event=PUBLISH")<log.indexOf("event=TIMEOUT"));assertTrue(log.indexOf("event=TIMEOUT")<log.indexOf("event=RETURN"));assertTrue(log.indexOf("event=RETURN")<log.indexOf("event=CONFIRM_ACK"));
            assertTrue(log.contains("logical_key_hash="));assertTrue(log.contains(result.correlationId()));assertTrue(log.contains("late=true"));
            assertFalse(log.contains(result.attemptId()));assertFalse(log.contains("00000000-0000-4000-8000-000000000002"));assertFalse(log.contains("memberId"));assertFalse(log.contains("do-not-log"));assertFalse(log.contains(CouponMqProtocolTest.payload()));
        } finally { logger.detachAppender(events);publisher.close(); }
    }
    @Test void defaultContextDoesNotActivatePublisherOrConnect() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                .withBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class,()->factory)
                .run(context->{assertFalse(context.containsBean("couponMqPublisher"));assertEquals(0,context.getBeansOfType(CouponMqPublisher.class).size());verify(factory,never()).createConnection();});
    }
}
