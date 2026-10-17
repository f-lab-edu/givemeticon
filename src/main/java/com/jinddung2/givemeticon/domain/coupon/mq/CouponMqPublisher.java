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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Inactive core. The caller supplies an unused, dedicated connection factory.
 * Closing retires this owner; uncertain capacity survives native cleanup until JVM exit.
 */
public final class CouponMqPublisher implements AutoCloseable {
    public enum Status { ACCEPTED, REJECTED, CHECKING, NOT_SENT }
    public enum Reason { CONFIRMED, RETURNED, NACK, TIMEOUT, CALLER_TIMEOUT, SEND_UNCERTAIN,
        CALLBACK_UNCERTAIN, FRAMEWORK_NACK, CLOSED, CAPACITY, WORKER_CAPACITY, PRE_SEND_FAILURE, INVALID_INPUT }
    public record Result(Status status, String attemptId, String correlationId, Reason reason) { }
    public enum CleanupState { NOT_REQUESTED, RUNNING, NOT_STARTED, ATTEMPT_RETURNED, FAILED, STALLED }
    public record CleanupObservation(long attemptId, CleanupState state, int activeTasks, int unknownCount, int trackedCount) { }

    private static final Logger LOG=LoggerFactory.getLogger(CouponMqPublisher.class);
    private static final class Shared {
        static final Semaphore SLOTS=new Semaphore(100);
        static final AtomicInteger THREAD_IDS=new AtomicInteger();
        static final ThreadPoolExecutor SENDERS=new ThreadPoolExecutor(0,16,60,TimeUnit.SECONDS,
                new SynchronousQueue<>(),r->{
                    var thread=new Thread(r,"coupon-mq-send-"+THREAD_IDS.incrementAndGet());thread.setDaemon(true);return thread;
                },new ThreadPoolExecutor.AbortPolicy());
        static final ThreadPoolExecutor CLEANERS=new ThreadPoolExecutor(0,1,60,TimeUnit.SECONDS,
                new SynchronousQueue<>(),r->{
                    var thread=new Thread(r,"coupon-mq-cleanup-"+THREAD_IDS.incrementAndGet());thread.setDaemon(true);return thread;
                },new ThreadPoolExecutor.AbortPolicy());
    }
    private final RabbitTemplate template;
    private final CachingConnectionFactory factory;
    private final Duration confirmBudget,callerBudget,cleanupBudget;
    private final ThreadPoolExecutor senders,cleaners;
    private final Semaphore slots;
    private final ConcurrentHashMap<String,Pending> pending=new ConcurrentHashMap<>();
    private final AtomicInteger activeTasks=new AtomicInteger();
    private final Object lifecycle=new Object();
    private volatile boolean closed;
    private CleanupAttempt cleanup;
    private static final java.util.concurrent.atomic.AtomicLong CLEANUP_IDS=new java.util.concurrent.atomic.AtomicLong();

    public CouponMqPublisher(CachingConnectionFactory dedicatedFactory) {
        this(new RabbitTemplate(dedicatedFactory),dedicatedFactory,Duration.ofSeconds(1));
    }
    CouponMqPublisher(RabbitTemplate template,CachingConnectionFactory factory,Duration confirmBudget) {
        this(template,factory,confirmBudget,Duration.ofSeconds(3),Duration.ofSeconds(5),Shared.SENDERS,Shared.CLEANERS,Shared.SLOTS);
    }
    // Concrete resources permit isolated test ownership, without resetting process quotas.
    CouponMqPublisher(RabbitTemplate template,CachingConnectionFactory factory,Duration confirmBudget,
                      Duration callerBudget,Duration cleanupBudget,ThreadPoolExecutor senders,
                      ThreadPoolExecutor cleaners,Semaphore slots) {
        if(confirmBudget.isNegative() || confirmBudget.compareTo(Duration.ofSeconds(1))>0
                || callerBudget.isNegative() || callerBudget.isZero() || callerBudget.compareTo(Duration.ofSeconds(3))>0
                || cleanupBudget.isNegative() || cleanupBudget.isZero() || cleanupBudget.compareTo(Duration.ofSeconds(5))>0)
            throw new IllegalArgumentException("invalid publisher wait budget");
        this.template=template;this.factory=factory;this.confirmBudget=confirmBudget;
        this.callerBudget=callerBudget;this.cleanupBudget=cleanupBudget;
        this.senders=senders;this.cleaners=cleaners;this.slots=slots;
        var raw=factory.getRabbitConnectionFactory();
        if(raw==null) throw new IllegalArgumentException("dedicated raw connection factory required");
        raw.setConnectionTimeout(1000);raw.setHandshakeTimeout(2000);raw.setChannelRpcTimeout(1000);
        raw.setRequestedHeartbeat(60);raw.setShutdownTimeout(10000);raw.setAutomaticRecoveryEnabled(false);raw.useBlockingIo();
        factory.setChannelCacheSize(100);factory.setChannelCheckoutTimeout(100);factory.setCloseTimeout(1000);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);factory.setPublisherReturns(true);
        template.setChannelTransacted(false);template.setRetryTemplate(null);
        template.setMandatory(true);template.setReturnsCallback(this::returned);
    }

    public Result publish(byte[] wire,long trustedEventId,int trustedMemberId,String exchange,String routingKey) {
        byte[] body;String attempt;
        try {
            body=CouponMqProtocol.publishBody(wire,trustedEventId,trustedMemberId);attempt=CouponMqProtocol.attemptId(body);
            if(exchange==null || routingKey==null || routingKey.isBlank()) throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) { return new Result(Status.NOT_SENT,null,null,Reason.INVALID_INPUT); }
        long started=System.nanoTime();
        Pending p=new Pending(attempt,CouponMqProtocol.sha256((trustedEventId+":"+trustedMemberId+":"+attempt).getBytes(StandardCharsets.UTF_8)));
        boolean admitted;
        synchronized(lifecycle) {
            if(closed) return new Result(Status.NOT_SENT,attempt,null,Reason.CLOSED);
            admitted=slots.tryAcquire();
            if(admitted) { pending.put(p.correlation.getId(),p);activeTasks.incrementAndGet(); }
        }
        if(!admitted) {
            LOG.info("coupon_mq event=CAPACITY process_used={} process_limit=100",100-slots.availablePermits());
            return new Result(Status.NOT_SENT,attempt,null,Reason.CAPACITY);
        }
        p.correlation.getFuture().whenComplete((confirm,error)->confirmed(p,confirm,error));
        try { senders.execute(()->send(p,body,exchange,routingKey)); }
        catch(RejectedExecutionException e) {
            synchronized(p) { p.definite=true;p.taskDone=true; }
            activeTasks.decrementAndGet();releaseIfFinished(p);
            p.response.complete(p.result(Status.NOT_SENT,Reason.WORKER_CAPACITY));event(p,"WORKER_CAPACITY");
        }
        try {
            long remaining=Math.max(0,callerBudget.toNanos()-(System.nanoTime()-started));
            return p.response.get(remaining,TimeUnit.NANOSECONDS);
        } catch(TimeoutException e) {
            Result result=p.result(Status.CHECKING,Reason.CALLER_TIMEOUT);p.response.complete(result);return result;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();Result result=p.result(Status.CHECKING,Reason.SEND_UNCERTAIN);
            p.response.complete(result);return result;
        } catch(ExecutionException e) { throw new IllegalStateException("unexpected publisher response failure"); }
    }

    private void send(Pending p,byte[] body,String exchange,String routingKey) {
        try {
            if(closed) {
                synchronized(p) { p.definite=true; }
                p.response.complete(p.result(Status.NOT_SENT,Reason.CLOSED));return;
            }
            var properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(p.correlation.getId());properties.setCorrelationId(p.correlation.getId());
            try {
                template.execute(channel->{
                    synchronized(p) { p.entered=true; }
                    event(p,"PUBLISH");
                    template.doSend(channel,exchange,routingKey,new Message(body,properties),true,p.correlation);return null;
                });
            } catch(RuntimeException e) {
                boolean entered;
                synchronized(p) { entered=p.entered;if(!entered) p.definite=true; }
                p.response.complete(p.result(entered?Status.CHECKING:Status.NOT_SENT,entered?Reason.SEND_UNCERTAIN:Reason.PRE_SEND_FAILURE));
                event(p,entered?"SEND_UNCERTAIN":"PRE_SEND_FAILURE");
            }
            try { p.response.get(confirmBudget.toNanos(),TimeUnit.NANOSECONDS); }
            catch(TimeoutException e) { p.response.complete(p.result(Status.CHECKING,Reason.TIMEOUT));event(p,"TIMEOUT"); }
            catch(InterruptedException e) {
                Thread.currentThread().interrupt();p.response.complete(p.result(Status.CHECKING,Reason.SEND_UNCERTAIN));event(p,"WAIT_INTERRUPTED");
            } catch(ExecutionException e) { throw new IllegalStateException("unexpected publisher response failure"); }
        } finally {
            // Logging may block: finish it before claiming that this worker's task ended.
            try { event(p,"TASK_EXIT"); }
            finally {
                synchronized(p) { p.taskDone=true; }
                activeTasks.decrementAndGet();releaseIfFinished(p);
            }
        }
    }

    private void returned(ReturnedMessage message) {
        Pending p=pending.get(message.getMessage().getMessageProperties().getMessageId());if(p==null) return;
        boolean late=p.response.isDone();synchronized(p) { p.returned=true; }
        p.response.complete(p.result(Status.REJECTED,Reason.RETURNED));event(p,"RETURN",late);
    }
    private void confirmed(Pending p,CorrelationData.Confirm confirm,Throwable error) {
        boolean late=p.response.isDone();
        if(error!=null || confirm==null) {
            p.response.complete(p.result(Status.CHECKING,Reason.CALLBACK_UNCERTAIN));event(p,"CALLBACK_UNCERTAIN",late);return;
        }
        boolean returned;
        synchronized(p) { returned=p.returned || p.correlation.getReturned()!=null; }
        if(!confirm.isAck() && confirm.getReason()!=null) {
            p.response.complete(p.result(returned?Status.REJECTED:Status.CHECKING,returned?Reason.RETURNED:Reason.FRAMEWORK_NACK));
            event(p,"FRAMEWORK_NACK",late);return;
        }
        synchronized(p) { p.definite=true; }
        p.response.complete(p.result(returned?Status.REJECTED:confirm.isAck()?Status.ACCEPTED:Status.REJECTED,
                returned?Reason.RETURNED:confirm.isAck()?Reason.CONFIRMED:Reason.NACK));
        releaseIfFinished(p);event(p,confirm.isAck()?"CONFIRM_ACK":"CONFIRM_NACK",late);
    }
    private void releaseIfFinished(Pending p) {
        boolean release;synchronized(p) { release=p.taskDone && p.definite; }
        if(release && pending.remove(p.correlation.getId(),p)) slots.release();
    }
    int pendingCount() { return pending.size(); }
    int activeTaskCount() { return activeTasks.get(); }

    @Override public void close() {
        CleanupAttempt attempt;boolean submit=false;
        synchronized(lifecycle) {
            closed=true;
            if(cleanup==null || cleanup.completion.getNow(CleanupState.RUNNING)==CleanupState.FAILED
                    || cleanup.completion.getNow(CleanupState.RUNNING)==CleanupState.NOT_STARTED) {
                cleanup=new CleanupAttempt(CLEANUP_IDS.incrementAndGet());submit=true;
            }
            attempt=cleanup;
        }
        for(Pending p:pending.values()) p.response.complete(p.result(Status.CHECKING,Reason.CLOSED));
        if(!submit) return;
        try {
            cleaners.execute(()->{
                CleanupState state;
                try { factory.destroy();state=CleanupState.ATTEMPT_RETURNED; }
                catch(RuntimeException e) { state=CleanupState.FAILED; }
                // Native close failures can be swallowed: no pending/slot release here.
                cleanupEvent(attempt,state);attempt.completion.complete(state);
            });
        } catch(RejectedExecutionException e) {
            attempt.completion.complete(CleanupState.NOT_STARTED);cleanupEvent(attempt,CleanupState.NOT_STARTED);
        }
    }
    public CleanupObservation cleanupStatus() {
        CleanupAttempt attempt;synchronized(lifecycle) { attempt=cleanup; }
        return observation(attempt,attempt==null?CleanupState.NOT_REQUESTED:attempt.completion.getNow(CleanupState.RUNNING));
    }
    /** Bounded observation, never cancellation. All callers of a running attempt observe its same id. */
    public CleanupObservation observeCleanup() {
        CleanupAttempt attempt;synchronized(lifecycle) { attempt=cleanup; }
        if(attempt==null) return observation(null,CleanupState.NOT_REQUESTED);
        try { return observation(attempt,attempt.completion.get(cleanupBudget.toNanos(),TimeUnit.NANOSECONDS)); }
        catch(TimeoutException e) { return observation(attempt,CleanupState.STALLED); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();return observation(attempt,CleanupState.RUNNING); }
        catch(ExecutionException e) { throw new IllegalStateException("unexpected cleanup observation failure"); }
    }
    private CleanupObservation observation(CleanupAttempt attempt,CleanupState state) {
        int unknown=0;for(Pending p:pending.values()) synchronized(p) { if(!p.definite) unknown++; }
        return new CleanupObservation(attempt==null?0:attempt.id,state,activeTasks.get(),unknown,pending.size());
    }
    private void cleanupEvent(CleanupAttempt attempt,CleanupState state) {
        CleanupObservation snapshot=observation(attempt,state);
        LOG.info("coupon_mq event=CLEANUP_{} attempt={} at={} active_tasks={} unknown={} tracked={} process_used={} process_limit=100",
                state,attempt.id,Instant.now(),snapshot.activeTasks(),snapshot.unknownCount(),snapshot.trackedCount(),100-slots.availablePermits());
    }
    private void event(Pending p,String type) { event(p,type,p.response.isDone()); }
    private void event(Pending p,String type,boolean late) {
        long sequence;synchronized(p) { sequence=++p.sequence; }
        LOG.info("coupon_mq event={} seq={} at={} logical_key_hash={} correlation={} late={} process_used={} process_limit=100",
                type,sequence,Instant.now(),p.logicalKeyHash,p.correlation.getId(),late,100-slots.availablePermits());
    }
    private static final class CleanupAttempt {
        final long id;final CompletableFuture<CleanupState> completion=new CompletableFuture<>();
        CleanupAttempt(long id) { this.id=id; }
    }
    private static final class Pending {
        final CorrelationData correlation=new CorrelationData(UUID.randomUUID().toString());
        final CompletableFuture<Result> response=new CompletableFuture<>();
        final String attemptId,logicalKeyHash;boolean entered,returned,definite,taskDone;long sequence;
        Pending(String attemptId,String logicalKeyHash) { this.attemptId=attemptId;this.logicalKeyHash=logicalKeyHash; }
        Result result(Status status,Reason reason) { return new Result(status,attemptId,correlation.getId(),reason); }
    }
}
