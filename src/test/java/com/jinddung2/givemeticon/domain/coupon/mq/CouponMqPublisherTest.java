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
    { action(template); }
    void action(RabbitTemplate target) {
        when(target.execute(any(org.springframework.amqp.rabbit.core.ChannelCallback.class))).thenAnswer(inv->
                ((org.springframework.amqp.rabbit.core.ChannelCallback<?>)inv.getArgument(0)).doInRabbit(mock(com.rabbitmq.client.Channel.class)));
    }
    List<CorrelationData> sent=new ArrayList<>();
    AtomicReference<RabbitTemplate.ReturnsCallback> returned=new AtomicReference<>();
    @org.junit.jupiter.api.AfterEach void cleanup() { publisher.close(); }
    byte[] body() throws Exception { return CouponMqProtocolTest.fixture("payload.json"); }
    void capture() {
        // Callback is installed in constructor; Mockito retains this real framework interface.
        var captor=org.mockito.ArgumentCaptor.forClass(RabbitTemplate.ReturnsCallback.class);
        verify(template).setReturnsCallback(captor.capture());returned.set(captor.getValue());
        doAnswer(inv->{sent.add(inv.getArgument(5));return null;}).when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
    }
    ReturnedMessage returnFor(CorrelationData data) {
        var props=new org.springframework.amqp.core.MessageProperties();props.setMessageId(data.getId());
        return new ReturnedMessage(new Message(new byte[]{1},props),312,"NO_ROUTE","exchange","key");
    }
    @Test void ackOnlyPersistentMandatoryAndFactoryFlags() throws Exception {
        doAnswer(inv->{Message m=inv.getArgument(3);CorrelationData c=inv.getArgument(5);
            assertArrayEquals(body(),m.getBody());assertEquals(MessageDeliveryMode.PERSISTENT,m.getMessageProperties().getDeliveryMode());
            assertEquals(c.getId(),m.getMessageProperties().getMessageId());
            c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(ACCEPTED,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(0,publisher.pendingCount());
        verify(template).setMandatory(true);verify(factory).setPublisherReturns(true);verify(factory).setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    }
    @Test void nackAndReturnAckAreRejections() throws Exception {
        doAnswer(inv->{CorrelationData c=inv.getArgument(5);c.getFuture().complete(new CorrelationData.Confirm(false,"not logged"));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());
        doAnswer(inv->{CorrelationData c=inv.getArgument(5);c.setReturned(returnFor(c));c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(0,publisher.pendingCount());
    }
    @Test void returnWithoutConfirmDoesNotFreeTracking() throws Exception {
        capture();doAnswer(inv->{CorrelationData c=inv.getArgument(5);sent.add(c);returned.get().returnedMessage(returnFor(c));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
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
        verify(template,never()).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));assertEquals(0,publisher.pendingCount());
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
        capture();doAnswer(inv->{sent.add(inv.getArgument(5));throw new AmqpException("password=do-not-log");})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        var result=publisher.publish(body(),7,91,"exchange","key");assertEquals(CHECKING,result.status());assertEquals(1,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(CHECKING,result.status());assertEquals(0,publisher.pendingCount());
    }
    @Test void exceptionalFutureRetainsUnknownUntilClose() throws Exception {
        doAnswer(inv->{((CorrelationData)inv.getArgument(5)).getFuture().completeExceptionally(new IllegalStateException("token=secret"));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(1,publisher.pendingCount());publisher.close();assertEquals(0,publisher.pendingCount());
    }
    @Test void shutdownDuringPublishReturnsCheckingAndLateAckIsHarmless() throws Exception {
        capture();doAnswer(inv->{sent.add(inv.getArgument(5));publisher.close();return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        var result=publisher.publish(body(),7,91,"exchange","key");assertEquals(CHECKING,result.status());assertEquals(0,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(CHECKING,result.status());assertEquals(0,publisher.pendingCount());
    }
    @Test void multiplePublishersShareTheProcessBudget() throws Exception {
        capture();var secondTemplate=mock(RabbitTemplate.class);var secondFactory=mock(CachingConnectionFactory.class);
        var second=new CouponMqPublisher(secondTemplate,secondFactory,Duration.ZERO);action(secondTemplate);
        try {
            for(int i=0;i<50;i++) {
                assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());
                assertEquals(CHECKING,second.publish(body(),7,91,"exchange","other").status());
            }
            assertEquals(NOT_SENT,publisher.publish(body(),7,91,"exchange","key").status());
            assertEquals(NOT_SENT,second.publish(body(),7,91,"exchange","other").status());
            verify(template,times(50)).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
            verify(secondTemplate,times(50)).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        } finally { publisher.close();second.close(); }
    }
    @Test void unknownSlotsStayOwnedUntilTransportDestructionReturns() throws Exception {
        capture();var otherTemplate=mock(RabbitTemplate.class);var otherFactory=mock(CachingConnectionFactory.class);
        var other=new CouponMqPublisher(otherTemplate,otherFactory,Duration.ZERO);action(otherTemplate);
        try {
            for(int i=0;i<100;i++) publisher.publish(body(),7,91,"exchange","key");
            doAnswer(inv->{assertEquals(NOT_SENT,other.publish(body(),7,91,"exchange","other").status());return null;}).when(factory).destroy();
            publisher.close();
            assertEquals(CHECKING,other.publish(body(),7,91,"exchange","other").status());
        } finally { publisher.close();other.close(); }
    }
    @Test void concurrentUnresolvedSendsNeverExceedOneHundred() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv->{calls.incrementAndGet();return null;}).when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
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
            doThrow(new AmqpException("token=do-not-log")).when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
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
    // Real Spring execute/doSend, with a transport that cannot open a network connection.
    static class Transport {
        final CachingConnectionFactory factory=mock(CachingConnectionFactory.class);
        final org.springframework.amqp.rabbit.connection.Connection connection=mock(org.springframework.amqp.rabbit.connection.Connection.class);
        final org.springframework.amqp.rabbit.connection.ChannelProxy channel=mock(org.springframework.amqp.rabbit.connection.ChannelProxy.class,
                withSettings().extraInterfaces(org.springframework.amqp.rabbit.connection.PublisherCallbackChannel.class));
        final RabbitTemplate template=new RabbitTemplate(factory);
        final CouponMqPublisher publisher=new CouponMqPublisher(template,factory,Duration.ZERO);
        final List<CorrelationData> correlations=new ArrayList<>();
        Transport() {
            when(factory.createConnection()).thenReturn(connection);
            when(factory.isPublisherConfirms()).thenReturn(true);
            when(factory.isPublisherReturns()).thenReturn(true);
            when(connection.createChannel(false)).thenReturn(channel);
            when(channel.getTargetChannel()).thenReturn(channel);
            when(channel.isPublisherConfirms()).thenReturn(true);
            when(channel.getNextPublishSeqNo()).thenReturn(1L);
            doAnswer(inv->{correlations.add(((org.springframework.amqp.rabbit.connection.PendingConfirm)inv.getArgument(2)).getCorrelationData());return null;})
                    .when((org.springframework.amqp.rabbit.connection.PublisherCallbackChannel)channel).addPendingConfirm(any(),anyLong(),any());
        }
    }
    org.springframework.amqp.AmqpConnectException failure(boolean shutdown) {
        return new org.springframework.amqp.AmqpConnectException(shutdown
                ? new com.rabbitmq.client.ShutdownSignalException(true,false,null,null)
                : new java.net.ConnectException("credentials-do-not-log"));
    }
    @Test void realTemplateConnectionAndChannelFailuresReturnSlotsBeforeAction() throws Exception {
        for(boolean shutdown:List.of(false,true)) for(boolean connectionFailure:List.of(false,true)) {
            var t=new Transport();
            try {
                var error=failure(shutdown);
                if(connectionFailure) when(t.factory.createConnection()).thenThrow(error);
                else when(t.connection.createChannel(false)).thenThrow(error);
                for(int i=0;i<105;i++) {
                    var result=t.publisher.publish(body(),7,91,"exchange","key");
                    assertEquals(NOT_SENT,result.status());assertEquals(0,t.publisher.pendingCount());
                    assertEquals("PRE_SEND_FAILURE",result.reason().name());
                }
                assertTrue(t.correlations.isEmpty());
                verify(t.channel,never()).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
                doReturn(t.connection).when(t.factory).createConnection();
                doReturn(t.channel).when(t.connection).createChannel(false);
                assertEquals(CHECKING,t.publisher.publish(body(),7,91,"exchange","key").status());
                assertEquals(1,t.correlations.size());
                var props=org.mockito.ArgumentCaptor.forClass(com.rabbitmq.client.AMQP.BasicProperties.class);
                verify(t.channel).basicPublish(eq("exchange"),eq("key"),eq(true),props.capture(),eq(body()));
                assertEquals(2,props.getValue().getDeliveryMode());
                assertEquals(t.correlations.get(0).getId(),props.getValue().getCorrelationId());
                assertEquals(t.correlations.get(0).getId(),props.getValue().getHeaders().get(
                        org.springframework.amqp.rabbit.connection.PublisherCallbackChannel.RETURNED_MESSAGE_CORRELATION_KEY));
                verify((org.springframework.amqp.rabbit.connection.PublisherCallbackChannel)t.channel).addListener(t.template);
                t.correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));
                assertEquals(0,t.publisher.pendingCount());
            } finally { t.publisher.close(); }
        }
    }
    @Test void realTemplateSameExceptionsAfterActionRemainUnknown() throws Exception {
        for(boolean shutdown:List.of(false,true)) {
            var t=new Transport();
            try {
                doThrow(failure(shutdown)).when(t.channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
                var result=t.publisher.publish(body(),7,91,"exchange","key");
                assertEquals(CHECKING,result.status());assertEquals(1,t.publisher.pendingCount());assertEquals(1,t.correlations.size());
                t.correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));
                assertEquals(0,t.publisher.pendingCount());assertEquals(CHECKING,result.status());
            } finally { t.publisher.close(); }
        }
    }
    @Test void failedDestroyCanBeExplicitlyRetriedWithoutOpeningPublisher() throws Exception {
        capture();var result=publisher.publish(body(),7,91,"exchange","key");
        doThrow(new IllegalStateException("password-do-not-log")).doNothing().when(factory).destroy();
        assertThrows(IllegalStateException.class,publisher::close);assertEquals(1,publisher.pendingCount());
        assertEquals(NOT_SENT,publisher.publish(body(),7,91,"exchange","key").status());
        publisher.close();assertEquals(0,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));
        publisher.close();verify(factory,times(2)).destroy();assertEquals(CHECKING,result.status());
    }

    @Test void interruptPreservesAttemptFlagAndUnknownSlotUntilLateConfirm() throws Exception {
        capture();
        try {
            Thread.currentThread().interrupt();
            var result=publisher.publish(body(),7,91,"exchange","key");
            assertEquals(CHECKING,result.status());assertEquals(CouponMqProtocol.attemptId(body()),result.attemptId());
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,publisher.pendingCount());
            sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));
            assertFalse(sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null)));
            assertEquals(0,publisher.pendingCount());assertEquals(CHECKING,result.status());
        } finally { Thread.interrupted(); }
    }
    @Test void concurrentCloseAndLateConfirmReleaseOnlyOnce() throws Exception {
        capture();publisher.publish(body(),7,91,"exchange","key");
        var entered=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertTrue(finish.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;}).when(factory).destroy();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var closing=executor.submit(publisher::close);
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            publisher.close();assertEquals(1,publisher.pendingCount());
            assertEquals(NOT_SENT,publisher.publish(body(),7,91,"exchange","key").status());
            sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));
            finish.countDown();closing.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0,publisher.pendingCount());verify(factory).destroy();
        } finally { finish.countDown();executor.shutdownNow(); }
    }
    @Test void confirmedResultSurvivesPostActionException() throws Exception {
        var t=new Transport();
        try {
            doAnswer(inv->{t.correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));throw failure(true);})
                    .when(t.channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
            assertEquals(ACCEPTED,t.publisher.publish(body(),7,91,"exchange","key").status());
            assertEquals(0,t.publisher.pendingCount());
        } finally { t.publisher.close(); }
    }

    @Test void preparationFailureDoesNotRegisterConfirmOrPublish() throws Exception {
        var t=new Transport();
        try {
            doThrow(failure(true)).when((org.springframework.amqp.rabbit.connection.PublisherCallbackChannel)t.channel).addListener(any());
            assertEquals(NOT_SENT,t.publisher.publish(body(),7,91,"exchange","key").status());
            assertEquals(0,t.publisher.pendingCount());assertTrue(t.correlations.isEmpty());
            verify(t.channel,never()).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
        } finally { t.publisher.close(); }
    }
    @Test void capacityAndFailureLogsExposeOwnershipWithoutSecrets() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponMqPublisher.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();logger.addAppender(events);
        var t=new Transport();
        try {
            when(t.factory.createConnection()).thenThrow(failure(false));
            t.publisher.publish(body(),7,91,"exchange","key");
            capture();for(int i=0;i<100;i++) publisher.publish(body(),7,91,"exchange","key");
            assertEquals(CouponMqPublisher.Reason.CAPACITY,publisher.publish(body(),7,91,"exchange","key").reason());
            doThrow(new IllegalStateException("password-do-not-log")).doNothing().when(factory).destroy();
            assertThrows(IllegalStateException.class,publisher::close);publisher.close();
            String log=events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(log.contains("event=PRE_SEND_FAILURE"));assertTrue(log.contains("event=CAPACITY"));
            assertTrue(log.contains("process_used=100 process_limit=100"));assertTrue(log.contains("event=CLEANUP_FAILED"));
            assertTrue(log.contains("event=CLEANUP_COMPLETE"));assertTrue(log.contains("process_used=0 process_limit=100"));
            assertFalse(log.contains("do-not-log"));assertFalse(log.contains("memberId"));assertFalse(log.contains(CouponMqProtocolTest.payload()));
        } finally { t.publisher.close();logger.detachAppender(events); }
    }

}
