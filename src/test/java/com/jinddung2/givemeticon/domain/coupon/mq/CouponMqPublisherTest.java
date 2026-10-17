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
    static class Scope implements AutoCloseable {
        final java.util.concurrent.Semaphore slots=new java.util.concurrent.Semaphore(100);
        final java.util.concurrent.ThreadPoolExecutor senders=new java.util.concurrent.ThreadPoolExecutor(0,16,1,java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        final java.util.concurrent.ThreadPoolExecutor cleaners=new java.util.concurrent.ThreadPoolExecutor(0,1,1,java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        CouponMqPublisher publisher(RabbitTemplate t,CachingConnectionFactory f) {
            return new CouponMqPublisher(t,f,Duration.ZERO,Duration.ofSeconds(1),Duration.ofMillis(50),senders,cleaners,slots);
        }
        @Override public void close() throws Exception {
            senders.shutdownNow();cleaners.shutdownNow();
            assertTrue(senders.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(cleaners.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    final Scope scope=new Scope();
    static void until(java.util.function.BooleanSupplier condition) {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.yield();
        assertTrue(condition.getAsBoolean(),"condition not reached");
    }
    static void idle(CouponMqPublisher p) { until(()->p.activeTaskCount()==0); }

    RabbitTemplate template=mock(RabbitTemplate.class);
    CachingConnectionFactory factory=dedicatedMock();
    static CachingConnectionFactory dedicatedMock() {
        var result=mock(CachingConnectionFactory.class);
        when(result.getRabbitConnectionFactory()).thenReturn(new com.rabbitmq.client.ConnectionFactory());return result;
    }
    CouponMqPublisher publisher=scope.publisher(template,factory);
    { action(template); }
    void action(RabbitTemplate target) {
        when(target.execute(any(org.springframework.amqp.rabbit.core.ChannelCallback.class))).thenAnswer(inv->
                ((org.springframework.amqp.rabbit.core.ChannelCallback<?>)inv.getArgument(0)).doInRabbit(mock(com.rabbitmq.client.Channel.class)));
    }
    List<CorrelationData> sent=new java.util.concurrent.CopyOnWriteArrayList<>();
    AtomicReference<RabbitTemplate.ReturnsCallback> returned=new AtomicReference<>();
    @org.junit.jupiter.api.AfterEach void cleanup() throws Exception { publisher.close();publisher.observeCleanup();scope.close(); }
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
        assertEquals(ACCEPTED,publisher.publish(body(),7,91,"exchange","key").status());idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
        verify(template).setMandatory(true);verify(factory).setPublisherReturns(true);verify(factory).setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    }
    @Test void nackAndReturnAckAreRejections() throws Exception {
        doAnswer(inv->{CorrelationData c=inv.getArgument(5);c.getFuture().complete(new CorrelationData.Confirm(false,null));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());
        doAnswer(inv->{CorrelationData c=inv.getArgument(5);c.setReturned(returnFor(c));c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
    }
    @Test void returnWithoutConfirmDoesNotFreeTracking() throws Exception {
        capture();doAnswer(inv->{CorrelationData c=inv.getArgument(5);sent.add(c);returned.get().returnedMessage(returnFor(c));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(REJECTED,publisher.publish(body(),7,91,"exchange","key").status());assertEquals(1,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
    }
    @Test void timeoutLateAckAndDoubleCallbackPreserveCallerResult() throws Exception {
        capture();var result=publisher.publish(body(),7,91,"exchange","key");assertEquals(CHECKING,result.status());assertEquals(1,publisher.pendingCount());
        assertTrue(sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null)));
        assertFalse(sent.get(0).getFuture().complete(new CorrelationData.Confirm(false,"duplicate")));
        assertEquals(CHECKING,result.status());idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
    }
    @Test void lateReturnAndCorrelationIsolation() throws Exception {
        capture();var first=publisher.publish(body(),7,91,"exchange","key");var second=publisher.publish(body(),7,91,"exchange","key");
        assertEquals(first.attemptId(),second.attemptId());assertNotEquals(first.correlationId(),second.correlationId());
        returned.get().returnedMessage(returnFor(sent.get(0)));assertEquals(2,publisher.pendingCount());
        sent.get(1).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(1,publisher.pendingCount());
        sent.get(0).setReturned(returnFor(sent.get(0)));sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
        returned.get().returnedMessage(returnFor(sent.get(0)));idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
        assertEquals(CHECKING,first.status());assertEquals(CHECKING,second.status());
    }
    @Test void invalidAndOversizeNeverSend() throws Exception {
        assertEquals(NOT_SENT,publisher.publish(CouponMqProtocolTest.fixture("null-token.json"),7,91,"exchange","key").status());
        assertEquals(NOT_SENT,publisher.publish(new byte[4097],7,91,"exchange","key").status());
        assertEquals(NOT_SENT,publisher.publish(body(),8,91,"exchange","key").status());
        verify(template,never()).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
    }

    @Test void sendExceptionMayHavePublishedAndKeepsSlot() throws Exception {
        capture();doAnswer(inv->{sent.add(inv.getArgument(5));throw new AmqpException("password=do-not-log");})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        var result=publisher.publish(body(),7,91,"exchange","key");assertEquals(CHECKING,result.status());assertEquals(1,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));assertEquals(CHECKING,result.status());idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());
    }


    @Test void multiplePublishersShareTheProcessBudget() throws Exception {
        capture();var secondTemplate=mock(RabbitTemplate.class);var secondFactory=dedicatedMock();
        var second=scope.publisher(secondTemplate,secondFactory);action(secondTemplate);
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
            capture();var result=publisher.publish(body(),7,91,"exchange","key");idle(publisher);
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
        final Scope scope=new Scope();
        final CachingConnectionFactory factory=dedicatedMock();
        final org.springframework.amqp.rabbit.connection.Connection connection=mock(org.springframework.amqp.rabbit.connection.Connection.class);
        final org.springframework.amqp.rabbit.connection.ChannelProxy channel=mock(org.springframework.amqp.rabbit.connection.ChannelProxy.class,
                withSettings().extraInterfaces(org.springframework.amqp.rabbit.connection.PublisherCallbackChannel.class));
        final RabbitTemplate template=new RabbitTemplate(factory);
        final CouponMqPublisher publisher=scope.publisher(template,factory);
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
                    assertEquals(NOT_SENT,result.status());idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(0,t.publisher.pendingCount());
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
                idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(0,t.publisher.pendingCount());
            } finally { t.publisher.close();t.publisher.observeCleanup();t.scope.close(); }
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
                idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(0,t.publisher.pendingCount());assertEquals(CHECKING,result.status());
            } finally { t.publisher.close();t.publisher.observeCleanup();t.scope.close(); }
        }
    }

    @Test void interruptPreservesAttemptFlagAndUnknownSlotUntilLateConfirm() throws Exception {
        capture();
        try {
            Thread.currentThread().interrupt();
            var result=publisher.publish(body(),7,91,"exchange","key");
            assertEquals(CHECKING,result.status());assertEquals(CouponMqProtocol.attemptId(body()),result.attemptId());
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,publisher.pendingCount());
            until(()->!sent.isEmpty());
            sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));
            assertFalse(sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null)));
            idle(publisher);until(()->publisher.pendingCount()==0);assertEquals(0,publisher.pendingCount());assertEquals(CHECKING,result.status());
        } finally { Thread.interrupted(); }
    }

    @Test void confirmedResultSurvivesPostActionException() throws Exception {
        var t=new Transport();
        try {
            doAnswer(inv->{t.correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));throw failure(true);})
                    .when(t.channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
            assertEquals(ACCEPTED,t.publisher.publish(body(),7,91,"exchange","key").status());
            idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(0,t.publisher.pendingCount());
        } finally { t.publisher.close();t.publisher.observeCleanup();t.scope.close(); }
    }

    @Test void preparationFailureDoesNotRegisterConfirmOrPublish() throws Exception {
        var t=new Transport();
        try {
            doThrow(failure(true)).when((org.springframework.amqp.rabbit.connection.PublisherCallbackChannel)t.channel).addListener(any());
            assertEquals(NOT_SENT,t.publisher.publish(body(),7,91,"exchange","key").status());
            idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(0,t.publisher.pendingCount());assertTrue(t.correlations.isEmpty());
            verify(t.channel,never()).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
        } finally { t.publisher.close();t.publisher.observeCleanup();t.scope.close(); }
    }

    static class RealTransport implements AutoCloseable {
        final Scope scope=new Scope();
        final com.rabbitmq.client.ConnectionFactory client=mock(com.rabbitmq.client.ConnectionFactory.class);
        final com.rabbitmq.client.Connection connection=mock(com.rabbitmq.client.Connection.class);
        final com.rabbitmq.client.Channel channel=mock(com.rabbitmq.client.Channel.class);
        final CachingConnectionFactory factory;
        final RabbitTemplate template;
        final CouponMqPublisher publisher;
        final List<CorrelationData> correlations=new ArrayList<>();
        final List<org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl> callbacks=new ArrayList<>();
        final java.util.ArrayDeque<Runnable> queued=new java.util.ArrayDeque<>();
        boolean defer;
        Runnable afterDestroy;
        RealTransport() throws Exception {
            when(client.newConnection(nullable(java.util.concurrent.ExecutorService.class),anyString())).thenReturn(connection);
            when(connection.isOpen()).thenReturn(true);when(channel.isOpen()).thenReturn(true);
            when(connection.createChannel()).thenReturn(channel);
            var sequence=new java.util.concurrent.atomic.AtomicLong(1);
            when(channel.getNextPublishSeqNo()).thenAnswer(inv->sequence.get());
            doAnswer(inv->{sequence.incrementAndGet();return null;}).when(channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
            factory=new CachingConnectionFactory(client) {
                @Override public void resetConnection() {
                    super.resetConnection();
                    if(afterDestroy!=null) afterDestroy.run();
                }
            };
            var executor=mock(java.util.concurrent.ExecutorService.class);
            doAnswer(inv->{Runnable job=inv.getArgument(0);if(defer) queued.add(job);else job.run();return null;}).when(executor).execute(any());
            factory.setPublisherChannelFactory((ch,ignored)->{
                var callback=new org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl(ch,executor);
                callbacks.add(callback);return callback;
            });
            template=new RabbitTemplate(factory);
            template.setCorrelationDataPostProcessor((message,c)->{correlations.add(c);return c;});
            publisher=new CouponMqPublisher(template,factory,Duration.ZERO,Duration.ofSeconds(3),Duration.ofMillis(50),scope.senders,scope.cleaners,scope.slots);
        }
        void shutdown() {
            callbacks.get(0).shutdownCompleted(new com.rabbitmq.client.ShutdownSignalException(true,false,
                    new com.rabbitmq.client.impl.AMQImpl.Connection.Close(320,"endpoint-password-do-not-log",0,0),connection));
        }
        void drain() { while(!queued.isEmpty()) queued.remove().run(); }
        @Override public void close() throws Exception { afterDestroy=null;defer=false;drain();publisher.close();publisher.observeCleanup();scope.close();factory.destroy(); }
    }
    @Test void springSyntheticNackRetainsUnknownSlotAfterTimeout() throws Exception {
        try(var t=new RealTransport()) {
            var result=t.publisher.publish(body(),7,91,"exchange","key");
            assertEquals(CHECKING,result.status());assertEquals(1,t.correlations.size());
            t.shutdown();
            var confirm=t.correlations.get(0).getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(confirm.isAck());assertNotNull(confirm.getReason());
            assertEquals(1,t.publisher.pendingCount());assertEquals(CHECKING,result.status());
            t.publisher.close();t.publisher.observeCleanup();idle(t.publisher);assertEquals(1,t.publisher.pendingCount());
        }
    }
    @Test void springBrokerNackHasNullReasonAndReleasesOnlyItsCorrelation() throws Exception {
        try(var t=new RealTransport()) {
            t.publisher.publish(body(),7,91,"exchange","key");
            t.publisher.publish(body(),7,91,"exchange","key");
            t.callbacks.get(0).handleNack(1,false);
            assertNull(t.correlations.get(0).getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS).getReason());
            assertEquals(1,t.publisher.pendingCount());
            t.callbacks.get(1).handleAck(2,false);idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(0,t.publisher.pendingCount());
        }
    }
    @Test void springSyntheticNackBeforeWaitIsCheckingWithStableReason() throws Exception {
        try(var t=new RealTransport()) {
            doAnswer(inv->{t.shutdown();return null;}).when(t.channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
            var result=t.publisher.publish(body(),7,91,"exchange","key");
            assertEquals(CHECKING,result.status());assertEquals("FRAMEWORK_NACK",result.reason().name());
            assertEquals(1,t.publisher.pendingCount());
        }
    }
    @Test void emptyFrameworkReasonAndStoredReturnPreserveOutcomeAndSlot() throws Exception {
        for(String reason:List.of("","endpoint-password-do-not-log")) {
            capture();
            doAnswer(inv->{CorrelationData c=inv.getArgument(5);c.setReturned(returnFor(c));
                c.getFuture().complete(new CorrelationData.Confirm(false,reason));return null;})
                    .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
            var result=publisher.publish(body(),7,91,"exchange","key");
            assertEquals(REJECTED,result.status());assertEquals(CouponMqPublisher.Reason.RETURNED,result.reason());
        }
        assertEquals(2,publisher.pendingCount());
    }

    @Test void springBrokerAckAndNackBeforeWaitProduceDefinitiveResults() throws Exception {
        for(boolean ack:List.of(true,false)) try(var t=new RealTransport()) {
            doAnswer(inv->{if(ack) t.callbacks.get(0).handleAck(1,false);else t.callbacks.get(0).handleNack(1,false);return null;})
                    .when(t.channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
            var result=t.publisher.publish(body(),7,91,"exchange","key");
            assertEquals(ack?ACCEPTED:REJECTED,result.status());
            assertEquals(ack?CouponMqPublisher.Reason.CONFIRMED:CouponMqPublisher.Reason.NACK,result.reason());
            assertNull(t.correlations.get(0).getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS).getReason());idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(0,t.publisher.pendingCount());
            t.factory.destroy();assertEquals(ack?ACCEPTED:REJECTED,result.status());
        }
    }
    @Test void springExternalFactoryDestroyIsNotOwnedPublisherCleanup() throws Exception {
        try(var t=new RealTransport()) {
            var result=t.publisher.publish(body(),7,91,"exchange","key");
            t.factory.destroy();
            assertNotNull(t.correlations.get(0).getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS).getReason());
            assertEquals(CHECKING,result.status());assertEquals(1,t.publisher.pendingCount());
            t.publisher.close();t.publisher.observeCleanup();idle(t.publisher);assertEquals(1,t.publisher.pendingCount());
        }
    }
    @Test void emptySyntheticReasonWithoutReturnIsUnknown() throws Exception {
        doAnswer(inv->{((CorrelationData)inv.getArgument(5)).getFuture().complete(new CorrelationData.Confirm(false,""));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        var result=publisher.publish(body(),7,91,"exchange","key");
        assertEquals(CHECKING,result.status());assertEquals("FRAMEWORK_NACK",result.reason().name());assertEquals(1,publisher.pendingCount());
    }

    @Test void springReturnBeforeSyntheticNackPreservesBothCallbackOrders() throws Exception {
        for(boolean delayReturn:List.of(false,true)) try(var t=new RealTransport()) {
            doAnswer(inv->{
                com.rabbitmq.client.AMQP.BasicProperties props=inv.getArgument(3);
                var headers=new java.util.HashMap<>(props.getHeaders());
                String key=org.springframework.amqp.rabbit.connection.PublisherCallbackChannel.RETURNED_MESSAGE_CORRELATION_KEY;
                headers.put(key,com.rabbitmq.client.impl.LongStringHelper.asLongString(headers.get(key).toString()));
                var returnedProps=props.builder().headers(headers).build();
                t.defer=delayReturn;
                t.callbacks.get(0).handle(new com.rabbitmq.client.Return(312,"NO_ROUTE","exchange","key",returnedProps,body()));
                assertNotNull(t.correlations.get(0).getReturned());
                // Shutdown generation can overtake the queued returns callback.
                t.defer=false;t.shutdown();
                return null;
            }).when(t.channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
            var result=t.publisher.publish(body(),7,91,"exchange","key");
            assertEquals(REJECTED,result.status());assertEquals(CouponMqPublisher.Reason.RETURNED,result.reason());
            assertEquals(1,t.publisher.pendingCount());t.drain();assertEquals(1,t.publisher.pendingCount());
        }
    }


    @Test void syntheticAndBrokerLogsDistinguishSourceWithoutRawReason() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponMqPublisher.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();logger.addAppender(events);
        try(var synthetic=new RealTransport();var broker=new RealTransport()) {
            synthetic.publisher.publish(body(),7,91,"exchange","key");synthetic.shutdown();
            broker.publisher.publish(body(),7,91,"exchange","key");broker.callbacks.get(0).handleNack(1,false);
            String log=events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(log.contains("event=FRAMEWORK_NACK"));assertTrue(log.contains("event=CONFIRM_NACK"));
            assertTrue(log.contains("process_used="));assertFalse(log.contains("do-not-log"));
            assertFalse(log.contains(CouponMqProtocolTest.payload()));
        } finally { logger.detachAppender(events); }
    }

    @Test void lifecycleNativeSettingsAreAppliedToDedicatedFactory() {
        var dedicated=new CachingConnectionFactory();var raw=dedicated.getRabbitConnectionFactory();
        raw.setRequestedHeartbeat(2);raw.setShutdownTimeout(0);raw.setAutomaticRecoveryEnabled(true);raw.useNio();
        var instance=new CouponMqPublisher(dedicated);
        try {
            assertEquals(1000,dedicated.getRabbitConnectionFactory().getConnectionTimeout());
            assertEquals(2000,dedicated.getRabbitConnectionFactory().getHandshakeTimeout());
            assertEquals(1000,dedicated.getRabbitConnectionFactory().getChannelRpcTimeout());
            assertEquals(1000,dedicated.getCloseTimeout());assertEquals(100,dedicated.getChannelCacheSize());
            assertEquals(100L,org.springframework.test.util.ReflectionTestUtils.getField(dedicated,"channelCheckoutTimeout"));
            assertEquals(60,raw.getRequestedHeartbeat());assertEquals(10000,raw.getShutdownTimeout());assertFalse(raw.isAutomaticRecoveryEnabled());
            assertEquals(false,org.springframework.test.util.ReflectionTestUtils.getField(raw,"nio"));
            assertEquals(Duration.ofSeconds(1),org.springframework.test.util.ReflectionTestUtils.getField(instance,"confirmBudget"));
        } finally { instance.close();instance.observeCleanup(); }
    }

    @Test void lifecycleCallerDeadlineAndAckDoNotReleaseBlockedTask() throws Exception {
        capture();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{sent.add(inv.getArgument(5));entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        var callers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            byte[] bytes=body();var call=callers.submit(()->publisher.publish(bytes,7,91,"exchange","key"));
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var result=call.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(CHECKING,result.status());assertEquals(CouponMqPublisher.Reason.CALLER_TIMEOUT,result.reason());
            var callback=callers.submit(()->sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null)));
            assertTrue(callback.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            assertEquals(1,publisher.pendingCount());assertEquals(1,publisher.activeTaskCount());
            release.countDown();until(()->publisher.pendingCount()==0);assertEquals(CHECKING,result.status());
        } finally { release.countDown();callers.shutdownNow();callers.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test void lifecycleReturnAndAckCallbacksRunWhileWriteIsBlocked() throws Exception {
        capture();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{sent.add(inv.getArgument(5));entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        var callers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            byte[] bytes=body();var call=callers.submit(()->publisher.publish(bytes,7,91,"exchange","key"));
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            callers.submit(()->returned.get().returnedMessage(returnFor(sent.get(0)))).get(200,java.util.concurrent.TimeUnit.MILLISECONDS);
            var result=call.get(1,java.util.concurrent.TimeUnit.SECONDS);assertEquals(REJECTED,result.status());
            callers.submit(()->sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null))).get(200,java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(1,publisher.pendingCount());assertFalse(sent.get(0).getFuture().complete(new CorrelationData.Confirm(false,null)));
            release.countDown();until(()->publisher.pendingCount()==0);assertEquals(REJECTED,result.status());
        } finally { release.countDown();callers.shutdownNow();callers.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test void lifecycleCleanupNeverReleasesUnknownAndRetriesOnlyFinishedFailure() throws Exception {
        capture();var result=publisher.publish(body(),7,91,"exchange","key");idle(publisher);
        doThrow(new IllegalStateException("do-not-log")).doNothing().when(factory).destroy();
        publisher.close();assertEquals(CouponMqPublisher.CleanupState.FAILED,publisher.observeCleanup().state());
        long first=publisher.cleanupStatus().attemptId();assertEquals(1,publisher.pendingCount());
        until(()->scope.cleaners.getActiveCount()==0);
        publisher.close();assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,publisher.observeCleanup().state());
        assertTrue(publisher.cleanupStatus().attemptId()>first);assertEquals(1,publisher.cleanupStatus().unknownCount());
        publisher.close();verify(factory,times(2)).destroy();assertEquals(NOT_SENT,publisher.publish(body(),7,91,"exchange","key").status());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));until(()->publisher.pendingCount()==0);
        assertEquals(CHECKING,result.status());
    }
    @Test void lifecycleCleanupWorkerIsSharedAndStalledAttemptCannotDuplicate() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;}).when(factory).destroy();
        var otherTemplate=mock(RabbitTemplate.class);action(otherTemplate);var otherFactory=dedicatedMock();
        var other=scope.publisher(otherTemplate,otherFactory);
        try {
            publisher.close();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));long id=publisher.cleanupStatus().attemptId();
            assertEquals(CouponMqPublisher.CleanupState.STALLED,publisher.observeCleanup().state());
            publisher.close();assertEquals(id,publisher.cleanupStatus().attemptId());verify(factory).destroy();
            other.close();assertEquals(CouponMqPublisher.CleanupState.NOT_STARTED,other.observeCleanup().state());verify(otherFactory,never()).destroy();
            release.countDown();assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,publisher.observeCleanup().state());
            until(()->scope.cleaners.getActiveCount()==0);
            other.close();assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,other.observeCleanup().state());verify(otherFactory).destroy();
            assertEquals(0,scope.cleaners.getQueue().size());assertEquals(1,scope.cleaners.getMaximumPoolSize());
        } finally { release.countDown(); }
    }
    @Test void lifecycleReentrantCloseDoesNotWaitForSendOrReleaseIt() throws Exception {
        capture();doAnswer(inv->{sent.add(inv.getArgument(5));publisher.close();assertEquals(1,publisher.pendingCount());return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        var result=publisher.publish(body(),7,91,"exchange","key");idle(publisher);
        assertEquals(CHECKING,result.status());assertEquals(CouponMqPublisher.Reason.CLOSED,result.reason());
        assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,publisher.observeCleanup().state());assertEquals(1,publisher.pendingCount());
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));until(()->publisher.pendingCount()==0);
    }
    @Test void lifecycleUnknownQuotaSurvivesSyntheticNacksCleanupAndNewOwners() throws Exception {
        doAnswer(inv->{((CorrelationData)inv.getArgument(5)).getFuture().complete(new CorrelationData.Confirm(false,""));return null;})
                .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        for(int i=0;i<100;i++) { assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());idle(publisher); }
        publisher.close();publisher.observeCleanup();assertEquals(100,publisher.pendingCount());assertEquals(0,scope.slots.availablePermits());
        var otherTemplate=mock(RabbitTemplate.class);action(otherTemplate);var other=scope.publisher(otherTemplate,dedicatedMock());
        assertEquals(CouponMqPublisher.Reason.CAPACITY,other.publish(body(),7,91,"exchange","other-event").reason());
        verify(otherTemplate,never()).execute(any(org.springframework.amqp.rabbit.core.ChannelCallback.class));
        other.close();other.observeCleanup();assertEquals(0,scope.slots.availablePermits());
    }
    @Test void lifecycleActualSpringCheckoutSaturationIsConfirmedUnsent() throws Exception {
        try(var t=new RealTransport()) {
            var channels=new ArrayList<com.rabbitmq.client.Channel>();
            try {
                var connection=t.factory.createConnection();for(int i=0;i<100;i++) channels.add(connection.createChannel(false));
                var result=t.publisher.publish(body(),7,91,"exchange","key");
                assertEquals(NOT_SENT,result.status());assertEquals(CouponMqPublisher.Reason.PRE_SEND_FAILURE,result.reason());
                idle(t.publisher);until(()->t.publisher.pendingCount()==0);assertEquals(100,t.scope.slots.availablePermits());
                assertTrue(t.correlations.isEmpty());verify(t.channel,never()).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
                channels.remove(0).close();
                assertEquals(CHECKING,t.publisher.publish(body(),7,91,"exchange","key").status());idle(t.publisher);
                assertEquals(1,t.correlations.size());
            } finally { for(var channel:channels) channel.close(); }
        }
    }
    @Test void lifecycleHiddenNativeCloseFailureKeepsUnknownSlots() throws Exception {
        try(var t=new RealTransport()) {
            t.publisher.publish(body(),7,91,"exchange","key");idle(t.publisher);
            doThrow(new java.io.IOException("endpoint-do-not-log")).when(t.connection).close(anyInt());
            t.publisher.close();assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,t.publisher.observeCleanup().state());
            verify(t.connection).close(1000);assertEquals(1,t.publisher.pendingCount());assertEquals(1,t.publisher.cleanupStatus().unknownCount());
        }
    }
    @Test void lifecycleConfirmedSlotRemainsOwnedDuringNativeFinally() throws Exception {
        var t=new Transport();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;}).when(t.channel).close();
        doAnswer(inv->{t.correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(t.channel).basicPublish(anyString(),anyString(),anyBoolean(),any(),any(byte[].class));
        try {
            assertEquals(ACCEPTED,t.publisher.publish(body(),7,91,"exchange","key").status());
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,t.publisher.pendingCount());
            assertEquals(1,t.publisher.activeTaskCount());release.countDown();until(()->t.publisher.pendingCount()==0);
        } finally { release.countDown();t.publisher.close();t.publisher.observeCleanup();t.scope.close(); }
    }
    @Test void lifecycleProductionOwnersShareSixteenWorkersAndIndependentCleanup() throws Exception {
        var templates=List.of(mock(RabbitTemplate.class),mock(RabbitTemplate.class));
        var factories=List.of(dedicatedMock(),dedicatedMock());var owners=new ArrayList<CouponMqPublisher>();
        var correlations=new java.util.concurrent.CopyOnWriteArrayList<CorrelationData>();
        var entered=new java.util.concurrent.CountDownLatch(16);var release=new java.util.concurrent.CountDownLatch(1);
        var cleaned=new java.util.concurrent.CountDownLatch(1);
        var callers=java.util.concurrent.Executors.newFixedThreadPool(16);
        for(int i=0;i<2;i++) {
            var target=templates.get(i);action(target);
            doAnswer(inv->{correlations.add(inv.getArgument(5));entered.countDown();assertTrue(release.await(8,java.util.concurrent.TimeUnit.SECONDS));return null;})
                    .when(target).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
            owners.add(new CouponMqPublisher(target,factories.get(i),Duration.ZERO));
        }
        doAnswer(inv->{cleaned.countDown();return null;}).when(factories.get(0)).destroy();
        try {
            byte[] bytes=body();var calls=new ArrayList<java.util.concurrent.Future<CouponMqPublisher.Result>>();
            for(int i=0;i<16;i++) { var owner=owners.get(i%2);calls.add(callers.submit(()->owner.publish(bytes,7,91,"exchange","key"))); }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(CouponMqPublisher.Reason.WORKER_CAPACITY,owners.get(1).publish(bytes,7,91,"exchange","key").reason());
            assertEquals(16,correlations.size());assertEquals(16,owners.stream().mapToInt(CouponMqPublisher::activeTaskCount).sum());
            owners.get(0).close();assertTrue(cleaned.await(1,java.util.concurrent.TimeUnit.SECONDS));
            for(var c:correlations) c.getFuture().complete(new CorrelationData.Confirm(true,null));
            assertEquals(16,owners.stream().mapToInt(CouponMqPublisher::pendingCount).sum());
            release.countDown();for(var call:calls) call.get(5,java.util.concurrent.TimeUnit.SECONDS);
            for(var owner:owners) until(()->owner.pendingCount()==0);
            assertEquals(Duration.ofSeconds(3),org.springframework.test.util.ReflectionTestUtils.getField(owners.get(0),"callerBudget"));
            assertEquals(Duration.ofSeconds(5),org.springframework.test.util.ReflectionTestUtils.getField(owners.get(0),"cleanupBudget"));
        } finally {
            release.countDown();for(var c:correlations) c.getFuture().complete(new CorrelationData.Confirm(true,null));
            for(var owner:owners) { owner.close();owner.observeCleanup();idle(owner); }
            callers.shutdownNow();assertTrue(callers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void lifecycleLatePreSendFailureAfterCallerDeadlineReturnsSlotOnlyAfterExit() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));throw new AmqpException("do-not-log");})
                .when(template).execute(any(org.springframework.amqp.rabbit.core.ChannelCallback.class));
        var callers=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            byte[] bytes=body();var call=callers.submit(()->publisher.publish(bytes,7,91,"exchange","key"));
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));var result=call.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(CHECKING,result.status());assertEquals(1,publisher.pendingCount());
            release.countDown();until(()->publisher.pendingCount()==0);assertEquals(CHECKING,result.status());
            verify(template,never()).doSend(any(),anyString(),anyString(),any(),anyBoolean(),any());
        } finally { release.countDown();callers.shutdownNow();callers.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test void lifecycleExceptionalFutureAndCleanupKeepUnknownWithoutSensitiveLogs() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponMqPublisher.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            doAnswer(inv->{((CorrelationData)inv.getArgument(5)).getFuture().completeExceptionally(new IllegalStateException("token-do-not-log"));return null;})
                    .when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
            assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());idle(publisher);
            publisher.close();assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,publisher.observeCleanup().state());
            until(()->scope.cleaners.getActiveCount()==0);assertEquals(1,publisher.pendingCount());
            String log=events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(log.contains("CALLBACK_UNCERTAIN"));assertTrue(log.contains("CLEANUP_ATTEMPT_RETURNED"));
            assertFalse(log.contains("CLEANUP_COMPLETE"));assertFalse(log.contains("do-not-log"));assertFalse(log.contains(CouponMqProtocolTest.payload()));
        } finally { logger.detachAppender(events); }
    }
    @Test void lifecycleProductionOwnersShareHundredSlotsWithoutCleanupReset() throws Exception {
        var correlations=new java.util.concurrent.CopyOnWriteArrayList<CorrelationData>();var owners=new ArrayList<CouponMqPublisher>();
        for(int i=0;i<2;i++) {
            var target=mock(RabbitTemplate.class);action(target);
            doAnswer(inv->{correlations.add(inv.getArgument(5));return null;}).when(target)
                    .doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
            owners.add(new CouponMqPublisher(target,dedicatedMock(),Duration.ZERO));
        }
        try {
            for(int i=0;i<100;i++) {var owner=owners.get(i%2);assertEquals(CHECKING,owner.publish(body(),7,91,"exchange","key").status());idle(owner);}
            owners.get(0).close();owners.get(0).observeCleanup();
            assertEquals(CouponMqPublisher.Reason.CAPACITY,owners.get(1).publish(body(),7,91,"exchange","key").reason());
            assertEquals(100,owners.stream().mapToInt(CouponMqPublisher::pendingCount).sum());
        } finally {
            for(var c:correlations) c.getFuture().complete(new CorrelationData.Confirm(true,null));
            for(var owner:owners) {owner.close();owner.observeCleanup();until(()->owner.pendingCount()==0);}
        }
    }

    @Test void lifecycleTaskExitBeforeWorkerHandoffRejectsWithoutSendingAndAllowsExplicitRetry() throws Exception {
        var exited=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var worker=new AtomicReference<Thread>();
        var controlled=new java.util.concurrent.ThreadPoolExecutor(0,1,1,java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.SynchronousQueue<Runnable>(),new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void afterExecute(Runnable task,Throwable error) {
                worker.set(Thread.currentThread());exited.countDown();
                try { assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
            }
        };
        var owner=new CouponMqPublisher(template,factory,Duration.ZERO,Duration.ofSeconds(1),Duration.ofMillis(50),controlled,scope.cleaners,scope.slots);
        capture();
        doAnswer(inv->{CorrelationData c=inv.getArgument(5);sent.add(c);
            c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        try {
            byte[] bytes=body();
            var first=owner.publish(bytes,7,91,"exchange","key");
            assertEquals(ACCEPTED,first.status());
            assertTrue(exited.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,owner.activeTaskCount());assertEquals(0,owner.pendingCount());
            assertEquals(100,scope.slots.availablePermits());assertEquals(1,controlled.getActiveCount());
            var rejected=owner.publish(bytes,7,91,"exchange","key");
            assertEquals(NOT_SENT,rejected.status());assertEquals(CouponMqPublisher.Reason.WORKER_CAPACITY,rejected.reason());
            assertEquals(first.attemptId(),rejected.attemptId());assertNotEquals(first.correlationId(),rejected.correlationId());
            verify(template,times(1)).execute(any(org.springframework.amqp.rabbit.core.ChannelCallback.class));
            verify(template,times(1)).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
            assertEquals(0,owner.activeTaskCount());assertEquals(0,owner.pendingCount());assertEquals(100,scope.slots.availablePermits());
            // Let this test worker retire naturally before retrying, with no sleep or handoff race.
            Thread firstWorker=worker.get();release.countDown();firstWorker.join(5000);assertFalse(firstWorker.isAlive());
            var retry=owner.publish(bytes,7,91,"exchange","key");
            assertEquals(ACCEPTED,retry.status());assertEquals(rejected.attemptId(),retry.attemptId());
            assertNotEquals(rejected.correlationId(),retry.correlationId());
            idle(owner);until(()->owner.pendingCount()==0);
            assertEquals(100,scope.slots.availablePermits());assertEquals(2,sent.size());
            assertFalse(sent.get(0).getFuture().complete(new CorrelationData.Confirm(true,null)));
            assertEquals(100,scope.slots.availablePermits());
            verify(template,times(2)).execute(any(org.springframework.amqp.rabbit.core.ChannelCallback.class));
            verify(template,times(2)).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        } finally {
            release.countDown();owner.close();owner.observeCleanup();controlled.shutdownNow();
            assertTrue(controlled.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void lifecycleAcceptedButNotStartedTaskCanBeProvenUnsentAfterClose() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var controlled=new java.util.concurrent.ThreadPoolExecutor(0,1,1,java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<Runnable>()) {
            @Override protected void beforeExecute(Thread thread,Runnable task) {
                entered.countDown();try { assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
            }
        };
        var owner=new CouponMqPublisher(template,factory,Duration.ZERO,Duration.ofSeconds(1),Duration.ofMillis(50),controlled,scope.cleaners,scope.slots);
        var callers=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            byte[] bytes=body();var call=callers.submit(()->owner.publish(bytes,7,91,"exchange","key"));
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));owner.close();
            assertEquals(CHECKING,call.get(1,java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(1,owner.pendingCount());release.countDown();until(()->owner.pendingCount()==0);
            verify(template,never()).execute(any(org.springframework.amqp.rabbit.core.ChannelCallback.class));
        } finally { release.countDown();owner.close();owner.observeCleanup();controlled.shutdownNow();assertTrue(controlled.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));callers.shutdownNow(); }
    }

    @Test void lifecycleConfirmCompletionCanReenterCloseWithoutPublisherMonitor() throws Exception {
        doAnswer(inv->{CorrelationData c=inv.getArgument(5);
            c.getFuture().whenComplete((confirm,error)->publisher.close());
            c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(template).doSend(any(com.rabbitmq.client.Channel.class),anyString(),anyString(),any(Message.class),eq(true),any(CorrelationData.class));
        assertEquals(CHECKING,publisher.publish(body(),7,91,"exchange","key").status());
        until(()->publisher.pendingCount()==0);assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,publisher.observeCleanup().state());
    }
    @Test void lifecycleConcurrentCloseCallersObserveOneRunningAttempt() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;}).when(factory).destroy();
        var callers=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs=new ArrayList<java.util.concurrent.Callable<Long>>();
            for(int i=0;i<12;i++) jobs.add(()->{publisher.close();return publisher.cleanupStatus().attemptId();});
            var calls=callers.invokeAll(jobs);assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            long id=publisher.cleanupStatus().attemptId();for(var call:calls) assertEquals(id,call.get());
            verify(factory).destroy();assertEquals(CouponMqPublisher.CleanupState.STALLED,publisher.observeCleanup().state());
            release.countDown();assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,publisher.observeCleanup().state());
        } finally { release.countDown();callers.shutdownNow();assertTrue(callers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)); }
    }
    @Test void lifecycleLateActualSyntheticCallbackCannotReleaseRetiredUnknown() throws Exception {
        try(var t=new RealTransport()) {
            t.publisher.publish(body(),7,91,"exchange","key");idle(t.publisher);t.defer=true;
            t.publisher.close();assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED,t.publisher.observeCleanup().state());
            assertFalse(t.correlations.get(0).getFuture().isDone());assertEquals(1,t.publisher.pendingCount());
            t.defer=false;t.drain();assertNotNull(t.correlations.get(0).getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS).getReason());
            assertEquals(1,t.publisher.pendingCount());assertEquals(99,t.scope.slots.availablePermits());
            assertFalse(t.correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true,null)));
            assertEquals(1,t.publisher.pendingCount());
        }
    }

}
