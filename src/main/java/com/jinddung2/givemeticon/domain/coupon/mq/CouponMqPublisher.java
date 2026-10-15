package com.jinddung2.givemeticon.domain.coupon.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Not a bean: the later readiness slice must explicitly own/activate this publisher.
 * The supplied connection factory is dedicated to this instance and destroyed on close.
 */
public final class CouponMqPublisher implements AutoCloseable {
    public enum Status { ACCEPTED, REJECTED, CHECKING, NOT_SENT }
    public enum Reason { CONFIRMED, RETURNED, NACK, TIMEOUT, SEND_UNCERTAIN, CALLBACK_UNCERTAIN, FRAMEWORK_NACK, CLOSED, CAPACITY, PRE_SEND_FAILURE, INVALID_INPUT }
    public record Result(Status status, String attemptId, String correlationId, Reason reason) { }
    private static final Logger LOG=LoggerFactory.getLogger(CouponMqPublisher.class);
    private final RabbitTemplate template;
    private final CachingConnectionFactory factory;
    private final Duration budget;
    private static final Semaphore SLOTS=new Semaphore(100);
    private final ConcurrentHashMap<String, Pending> pending=new ConcurrentHashMap<>();
    private final Object lifecycle=new Object();
    private volatile boolean closed;
    private volatile boolean cleanupComplete;
    private final AtomicBoolean cleaning=new AtomicBoolean();

    public CouponMqPublisher(CachingConnectionFactory dedicatedFactory) {
        this(new RabbitTemplate(dedicatedFactory),dedicatedFactory,Duration.ofSeconds(1));
    }
    CouponMqPublisher(RabbitTemplate template,CachingConnectionFactory factory,Duration budget) {
        if(budget.isNegative() || budget.compareTo(Duration.ofSeconds(1))>0) throw new IllegalArgumentException("invalid confirm budget");
        this.template=template;this.factory=factory;this.budget=budget;
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        template.setMandatory(true);
        template.setReturnsCallback(this::returned);
    }
    public Result publish(byte[] wire,long trustedEventId,int trustedMemberId,String exchange,String routingKey) {
        byte[] body;String attempt;
        try {
            body=CouponMqProtocol.publishBody(wire,trustedEventId,trustedMemberId);
            attempt=CouponMqProtocol.attemptId(body);
            if(exchange==null || routingKey==null || routingKey.isBlank()) throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) { return new Result(Status.NOT_SENT,null,null,Reason.INVALID_INPUT); }
        Pending p;
        synchronized(lifecycle) {
            if(closed) return new Result(Status.NOT_SENT,attempt,null,Reason.CLOSED);
            if(!SLOTS.tryAcquire()) {
                capacityEvent("CAPACITY");
                return new Result(Status.NOT_SENT,attempt,null,Reason.CAPACITY);
            }
            p=new Pending(attempt,CouponMqProtocol.sha256((trustedEventId+":"+trustedMemberId+":"+attempt).getBytes(StandardCharsets.UTF_8)));
            pending.put(p.correlation.getId(),p);
        }
        synchronized(p) {
            if(closed) { release(p);return p.result(Status.NOT_SENT,Reason.CLOSED); }
            p.correlation.getFuture().whenComplete((confirm,error)->confirmed(p,confirm,error));
            MessageProperties properties=new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(p.correlation.getId());
            properties.setCorrelationId(p.correlation.getId());
            try {
                template.execute(channel->{
                    p.entered=true;
                    event(p,"PUBLISH",false);
                    template.doSend(channel,exchange,routingKey,new Message(body,properties),true,p.correlation);
                    return null;
                });
            }
            catch(RuntimeException e) {
                if(!p.entered) {
                    event(p,"PRE_SEND_FAILURE",p.response.isDone());
                    p.response.complete(p.result(Status.NOT_SENT,Reason.PRE_SEND_FAILURE));
                    release(p);
                } else {
                    event(p,"SEND_UNCERTAIN",p.response.isDone());
                    p.response.complete(p.result(Status.CHECKING,Reason.SEND_UNCERTAIN));
                }
            }
        }
        try { return p.response.get(budget.toNanos(),TimeUnit.NANOSECONDS); }
        catch(TimeoutException e) {
            synchronized(p) {
                event(p,"TIMEOUT",false);
                Result result=p.result(Status.CHECKING,Reason.TIMEOUT);p.response.complete(result);
                return result; // Never use a confirm that races after this wait timeout.
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized(p) { event(p,"WAIT_INTERRUPTED",p.response.isDone());Result result=p.result(Status.CHECKING,Reason.SEND_UNCERTAIN);p.response.complete(result);return result; }
        } catch(ExecutionException e) { throw new IllegalStateException("unexpected publisher response failure"); }
    }
    private void returned(ReturnedMessage message) {
        Pending p=pending.get(message.getMessage().getMessageProperties().getMessageId());
        if(p==null) return;
        synchronized(p) {
            p.returned=true;event(p,"RETURN",p.response.isDone());
            p.response.complete(p.result(Status.REJECTED,Reason.RETURNED));
            // Hold capacity until confirm; RabbitTemplate still tracks this transmission.
        }
    }
    private void confirmed(Pending p,CorrelationData.Confirm confirm,Throwable error) {
        synchronized(p) {
            boolean late=p.response.isDone();
            if(error!=null || confirm==null) {
                event(p,"CALLBACK_UNCERTAIN",late);
                p.response.complete(p.result(Status.CHECKING,Reason.CALLBACK_UNCERTAIN));
                return; // Unknown tracking stays bounded/fail-closed until explicit close.
            }
            // Spring 3.0.8: broker nacks have null reason; framework shutdown nacks do not.
            // A synthetic callback proves neither broker rejection nor owned transport cleanup.
            if(!confirm.isAck() && confirm.getReason()!=null) {
                event(p,"FRAMEWORK_NACK",late);
                if(p.returned || p.correlation.getReturned()!=null) p.response.complete(p.result(Status.REJECTED,Reason.RETURNED));
                else p.response.complete(p.result(Status.CHECKING,Reason.FRAMEWORK_NACK));
                return;
            }
            event(p,confirm.isAck()?"CONFIRM_ACK":"CONFIRM_NACK",late);
            if(p.returned || p.correlation.getReturned()!=null) p.response.complete(p.result(Status.REJECTED,Reason.RETURNED));
            else if(!confirm.isAck()) p.response.complete(p.result(Status.REJECTED,Reason.NACK));
            else p.response.complete(p.result(Status.ACCEPTED,Reason.CONFIRMED));
            release(p);
        }
    }
    private void release(Pending p) { if(pending.remove(p.correlation.getId(),p)) SLOTS.release(); }
    int pendingCount() { return pending.size(); }
    @Override public void close() {
        synchronized(lifecycle) { closed=true; }
        // A concurrent/reentrant close does not wait while holding a Pending monitor.
        // After a failed destroy, a later explicit close can acquire cleanup again.
        if(cleanupComplete || !cleaning.compareAndSet(false,true)) return;
        try {
            if(cleanupComplete) return;
            for(Pending p:pending.values()) synchronized(p) {
                event(p,"CLOSE_UNRESOLVED",p.response.isDone());p.response.complete(p.result(Status.CHECKING,Reason.CLOSED));
            }
            factory.destroy();
            for(Pending p:pending.values()) synchronized(p) { release(p); }
            cleanupComplete=true;
            capacityEvent("CLEANUP_COMPLETE");
        } catch(RuntimeException e) {
            capacityEvent("CLEANUP_FAILED");
            throw e;
        } finally { cleaning.set(false); }
    }
    private static void capacityEvent(String type) {
        LOG.info("coupon_mq event={} at={} process_used={} process_limit=100",type,Instant.now(),100-SLOTS.availablePermits());
    }
    private static void event(Pending p,String type,boolean late) {
        // Hash the logical key; never log member IDs, token, body, routing or exception text.
        LOG.info("coupon_mq event={} seq={} at={} logical_key_hash={} correlation={} late={} process_used={} process_limit=100",
                type,++p.sequence,Instant.now(),p.logicalKeyHash,p.correlation.getId(),late,100-SLOTS.availablePermits());
    }
    private static final class Pending {
        final CorrelationData correlation=new CorrelationData(UUID.randomUUID().toString());
        final CompletableFuture<Result> response=new CompletableFuture<>();
        final String attemptId,logicalKeyHash;
        boolean returned,entered;long sequence;
        Pending(String attemptId,String logicalKeyHash) { this.attemptId=attemptId;this.logicalKeyHash=logicalKeyHash; }
        Result result(Status status,Reason reason) { return new Result(status,attemptId,correlation.getId(),reason); }
    }
}
