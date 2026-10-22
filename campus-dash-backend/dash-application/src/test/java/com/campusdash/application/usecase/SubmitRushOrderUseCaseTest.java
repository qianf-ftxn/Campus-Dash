package com.campusdash.application.usecase;

import com.campusdash.domain.campaign.model.*;
import com.campusdash.domain.campaign.ports.*;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SubmitRushOrderUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-22T03:31:00Z");

    @Test
    void submit_only_enqueues_and_returns_processing() {
        CapturingQueue queue = new CapturingQueue(false);
        FakeReservations reservations = new FakeReservations();
        SubmitRushOrderUseCase useCase = useCase(queue, reservations);

        var result = useCase.submit(new SubmitRushOrderUseCase.Command(
                90001, 1001, "rush:90001:request-0001", "帮我带饭", 1500));

        assertEquals("PROCESSING", result.status());
        assertNotNull(queue.message);
        assertEquals(1001, queue.message.userId());
        assertEquals(1, queue.calls);
        assertEquals(1, reservations.queuedCalls);
    }

    @Test
    void queue_failure_never_falls_back_to_synchronous_creation() {
        SubmitRushOrderUseCase useCase = useCase(new CapturingQueue(true), new FakeReservations());
        BizException ex = assertThrows(BizException.class, () -> useCase.submit(
                new SubmitRushOrderUseCase.Command(90001, 1001,
                        "rush:90001:request-0002", "帮我带饭", 1500)));
        assertEquals(ErrorCode.RUSH_QUEUE_UNAVAILABLE, ex.code());
    }

    @Test
    void invalid_request_id_is_rejected_before_queue() {
        CapturingQueue queue = new CapturingQueue(false);
        BizException ex = assertThrows(BizException.class, () -> useCase(queue, new FakeReservations()).submit(
                new SubmitRushOrderUseCase.Command(90001, 1001, "bad key", "帮我带饭", 1500)));
        assertEquals(ErrorCode.INVALID_REQUEST_ID, ex.code());
        assertEquals(0, queue.calls);
    }

    private static SubmitRushOrderUseCase useCase(CapturingQueue queue, FakeReservations reservations) {
        RushCampaign campaign = new RushCampaign(90001, 1, "午餐",
                NOW.minusSeconds(60), NOW.plusSeconds(60), 500, 0,
                RushCampaignStatus.ENABLED, 0);
        RushCampaignRepository campaigns = new RushCampaignRepository() {
            @Override public Optional<RushCampaign> findById(long id) { return Optional.of(campaign); }
            @Override public List<RushCampaign> findByCampus(long campusId) { return List.of(campaign); }
            @Override public int casIncrementSold(long campaignId) { throw new AssertionError("在线受理不得写活动库存"); }
            @Override public long countSuccessfulRequests(long campaignId) { return 0; }
        };
        RushRequestRepository requests = new EmptyRequests();
        return new SubmitRushOrderUseCase(campaigns, requests, reservations, queue,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class CapturingQueue implements RushOrderQueuePort {
        final boolean fail;
        int calls;
        RushOrderMessage message;
        CapturingQueue(boolean fail) { this.fail = fail; }
        @Override public void enqueue(RushOrderMessage message) {
            calls++;
            if (fail) throw new IllegalStateException("broker down");
            this.message = message;
        }
    }

    private static final class FakeReservations implements RushReservationPort {
        int queuedCalls;
        @Override public void cacheQueued(RushOrderMessage message) { queuedCalls++; }
        @Override public Optional<RushRequest> findCached(String id) { return Optional.empty(); }
        @Override public RushReservationOutcome reserve(long c,long u,String r,Instant n) { throw new AssertionError(); }
        @Override public boolean rollback(long c,long u,String r,RushRequestStatus s) { return false; }
        @Override public void initialize(long c,int q) { throw new AssertionError(); }
        @Override public void cacheResult(RushRequest r) { throw new AssertionError(); }
        @Override public List<RushReservationSnapshot> findStaleProcessing(Instant b,int l) { return List.of(); }
        @Override public long remainingStock(long campaignId) { return -1; }
        @Override public long participantCount(long campaignId) { return 0; }
    }

    private static final class EmptyRequests implements RushRequestRepository {
        @Override public Optional<RushRequest> findByRequestId(String id) { return Optional.empty(); }
        @Override public Optional<RushRequest> findByCampaignAndUser(long c,long u) { return Optional.empty(); }
        @Override public void insertReserved(RushOrderData d) { throw new AssertionError(); }
        @Override public void markSuccess(String r,long e) { throw new AssertionError(); }
        @Override public void saveFailure(RushOrderData d,RushRequestStatus s,String e) { throw new AssertionError(); }
        @Override public void markFailure(String r,RushRequestStatus s,String e) { throw new AssertionError(); }
        @Override public List<RushRequest> findStaleProcessing(Instant b,int l) { return List.of(); }
        @Override public List<RushRequest> findSuccessfulSince(Instant s,int l) { return List.of(); }
    }
}
