package com.campusdash.application.usecase;

import com.campusdash.domain.campaign.model.*;
import com.campusdash.domain.campaign.ports.*;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRushOrderUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-22T03:31:00Z");

    @Test
    void replay_of_failed_terminal_request_retries_idempotent_redis_compensation() {
        RushRequest failed = request(RushRequestStatus.FAILED_INSUFFICIENT_BALANCE);
        FakeReservations reservations = new FakeReservations(false);
        RecordingNotifier notifier = new RecordingNotifier();
        ProcessRushOrderUseCase useCase = useCase(failed, reservations, notifier);

        assertEquals(ProcessRushOrderUseCase.Outcome.COMPLETED, useCase.process(message()));
        assertEquals(1, reservations.rollbackCalls);
        assertSame(failed, reservations.cached);
        assertSame(failed, notifier.notified);
    }

    @Test
    void redis_outage_while_repairing_failed_terminal_request_must_request_mq_retry() {
        FakeReservations reservations = new FakeReservations(true);
        ProcessRushOrderUseCase useCase = useCase(
                request(RushRequestStatus.FAILED_INSUFFICIENT_BALANCE),
                reservations, new RecordingNotifier());

        assertThrows(IllegalStateException.class, () -> useCase.process(message()));
        assertEquals(1, reservations.rollbackCalls);
        assertNull(reservations.cached);
    }

    @Test
    void replay_of_success_never_returns_activity_stock() {
        RushRequest success = new RushRequest("rush:90001:terminal-1", 90001, 1, 1001,
                RushRequestStatus.SUCCESS, "午餐", 1500, 70001L, null,
                NOW, NOW, NOW);
        FakeReservations reservations = new FakeReservations(false);

        assertEquals(ProcessRushOrderUseCase.Outcome.COMPLETED,
                useCase(success, reservations, new RecordingNotifier()).process(message()));
        assertEquals(0, reservations.rollbackCalls);
    }

    private static ProcessRushOrderUseCase useCase(RushRequest request,
                                                    FakeReservations reservations,
                                                    RecordingNotifier notifier) {
        RushCampaign campaign = new RushCampaign(90001, 1, "午餐",
                NOW.minusSeconds(60), NOW.plusSeconds(60), 500, 0,
                RushCampaignStatus.ENABLED, 0);
        RushCampaignRepository campaigns = new RushCampaignRepository() {
            @Override public Optional<RushCampaign> findById(long id) { return Optional.of(campaign); }
            @Override public List<RushCampaign> findByCampus(long campusId) { return List.of(campaign); }
            @Override public int casIncrementSold(long campaignId) { return 0; }
            @Override public long countSuccessfulRequests(long campaignId) { return 0; }
        };
        RushRequestRepository requests = new RushRequestRepository() {
            @Override public Optional<RushRequest> findByRequestId(String id) { return Optional.of(request); }
            @Override public Optional<RushRequest> findByCampaignAndUser(long c,long u) { return Optional.of(request); }
            @Override public void insertReserved(RushOrderData data) { throw new AssertionError(); }
            @Override public void markSuccess(String requestId,long errandId) { throw new AssertionError(); }
            @Override public void saveFailure(RushOrderData data,RushRequestStatus status,String error) { throw new AssertionError(); }
            @Override public void markFailure(String requestId,RushRequestStatus status,String error) { throw new AssertionError(); }
            @Override public List<RushRequest> findStaleProcessing(Instant before,int limit) { return List.of(); }
            @Override public List<RushRequest> findSuccessfulSince(Instant since,int limit) { return List.of(); }
        };
        return new ProcessRushOrderUseCase(campaigns, requests, reservations,
                null, noOp(GrabSlotPort.class), noOp(ErrandCachePort.class), notifier);
    }

    private static RushOrderMessage message() {
        return new RushOrderMessage(1, "rush:90001:terminal-1", 90001, 1, 1001,
                NOW, "午餐", 1500);
    }

    private static RushRequest request(RushRequestStatus status) {
        return new RushRequest("rush:90001:terminal-1", 90001, 1, 1001,
                status, "午餐", 1500, null, "INSUFFICIENT_BALANCE",
                NOW, NOW, NOW);
    }

    private static final class FakeReservations implements RushReservationPort {
        private final boolean failRollback;
        private int rollbackCalls;
        private RushRequest cached;

        private FakeReservations(boolean failRollback) { this.failRollback = failRollback; }

        @Override public boolean rollback(long c,long u,String r,RushRequestStatus s) {
            rollbackCalls++;
            if (failRollback) throw new IllegalStateException("redis down");
            return true;
        }
        @Override public void cacheResult(RushRequest request) { cached = request; }
        @Override public RushReservationOutcome reserve(long c,long u,String r,Instant n) { throw new AssertionError(); }
        @Override public void initialize(long c,int q) { throw new AssertionError(); }
        @Override public void cacheQueued(RushOrderMessage message) { throw new AssertionError(); }
        @Override public Optional<RushRequest> findCached(String id) { return Optional.empty(); }
        @Override public List<RushReservationSnapshot> findStaleProcessing(Instant b,int l) { return List.of(); }
        @Override public long remainingStock(long campaignId) { return -1; }
        @Override public long participantCount(long campaignId) { return 0; }
    }

    private static final class RecordingNotifier implements RushResultNotifier {
        private RushRequest notified;
        @Override public void notifyResult(RushRequest request) { notified = request; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T noOp(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> result = method.getReturnType();
            if (result == boolean.class) return false;
            if (result == int.class) return 0;
            if (result == long.class) return 0L;
            if (result == Optional.class) return Optional.empty();
            if (result == List.class) return List.of();
            return null;
        });
    }
}
