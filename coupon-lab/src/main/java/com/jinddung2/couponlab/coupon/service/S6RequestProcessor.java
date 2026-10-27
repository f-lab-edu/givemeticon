package com.jinddung2.couponlab.coupon.service;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.mapper.S6RequestMapper;
import com.jinddung2.couponlab.coupon.model.CouponStock;
import com.jinddung2.couponlab.coupon.model.S6RequestRecord;

/**
 * S6: the decision, one transaction per request. It is the only place that touches stock, so the transaction ends (commit)
 * before the listener returns and the broker is told to ACK.
 * Order: a request id that already has a row returns that row (a redelivery or a client retry decides nothing again); a
 * member who already holds a coupon of this event is ALREADY_ISSUED without using a seat; otherwise the conditional UPDATE
 * takes a seat (SOLD_OUT when it changes no row, FAILED/UNKNOWN_EVENT when the event does not exist). A win is written to
 * coupon_request and to coupon_issue (message_id = request id, which is what the harness counts). UNIQUE (request_id) and
 * UNIQUE (event_id, issued_member_id) are the backstop: a violation rolls the whole transaction back, seat included.
 */
@Service
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")
public class S6RequestProcessor {

    public static final String ISSUED = "ISSUED";
    public static final String SOLD_OUT = "SOLD_OUT";
    public static final String ALREADY_ISSUED = "ALREADY_ISSUED";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN_EVENT = "UNKNOWN_EVENT";

    private final S6RequestMapper requestMapper;
    private final CouponMapper couponMapper;

    public S6RequestProcessor(S6RequestMapper requestMapper, CouponMapper couponMapper) {
        this.requestMapper = requestMapper;
        this.couponMapper = couponMapper;
    }

    /** What process() decided or found; duplicate = the request id was already decided by an earlier delivery. */
    public record Outcome(String status, Integer seq, boolean duplicate) {
    }

    @Transactional
    public Outcome process(S6RequestMessage message) {
        S6RequestRecord existing = requestMapper.selectByRequestId(message.requestId());
        if (existing != null) {
            return new Outcome(existing.status(), existing.seq(), true);
        }
        long eventId = message.eventId();
        long memberId = message.memberId();
        Instant acceptedAt = Instant.ofEpochMilli(message.acceptedAt());
        if (requestMapper.existsIssuedForMember(eventId, memberId)) {
            requestMapper.insertRequest(message.requestId(), eventId, memberId, ALREADY_ISSUED, null, null, acceptedAt);
            return new Outcome(ALREADY_ISSUED, null, false);
        }
        if (requestMapper.decrementStock(eventId) == 1) {
            CouponStock stock = requestMapper.selectStock(eventId);
            int seq = stock.totalQuantity() - stock.remainingQuantity();
            requestMapper.insertRequest(message.requestId(), eventId, memberId, ISSUED, seq, null, acceptedAt);
            couponMapper.insertIssued(eventId, memberId, seq, message.requestId());
            return new Outcome(ISSUED, seq, false);
        }
        if (couponMapper.selectTotal(eventId) == null) {
            requestMapper.insertRequest(message.requestId(), eventId, memberId, FAILED, null, UNKNOWN_EVENT, acceptedAt);
            return new Outcome(FAILED, null, false);
        }
        requestMapper.insertRequest(message.requestId(), eventId, memberId, SOLD_OUT, null, null, acceptedAt);
        return new Outcome(SOLD_OUT, null, false);
    }

    /** Records FAILED for a request the Consumer gave up on; a request id that already has a row is left as it is. */
    @Transactional
    public void recordFailed(S6RequestMessage message, String reason) {
        if (requestMapper.selectByRequestId(message.requestId()) == null) {
            requestMapper.insertRequest(message.requestId(), message.eventId(), message.memberId(), FAILED, null, reason,
                    Instant.ofEpochMilli(message.acceptedAt()));
        }
    }

    /** The stored result of a request id, or null while it is not in the database (PENDING). */
    public S6RequestRecord find(String requestId) {
        return requestMapper.selectByRequestId(requestId);
    }
}
