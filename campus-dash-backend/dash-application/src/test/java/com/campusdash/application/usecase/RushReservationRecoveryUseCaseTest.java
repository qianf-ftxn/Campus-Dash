package com.campusdash.application.usecase;

import com.campusdash.domain.campaign.model.*;
import com.campusdash.domain.campaign.ports.RushRequestRepository;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.SlotOutcome;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RushReservationRecoveryUseCaseTest {

    @Test
    void redis_orphan_is_rolled_back_marked_dead_and_cached() {
        Instant old = Instant.now().minusSeconds(300);
        RushReservationSnapshot orphan = new RushReservationSnapshot(
                "rush:recovery:request-1", 90001, 1, 1001,
                RushRequestStatus.RESERVED, "午餐代购", 100, old, old);
        FakeRequests requests = new FakeRequests();
        FakeReservations reservations = new FakeReservations(orphan);
        RecordingTransaction transaction = new RecordingTransaction(requests);

        RushReservationRecoveryUseCase useCase = new RushReservationRecoveryUseCase(
                requests, reservations, transaction,
                noOp(ErrandRepository.class), new NoOpSlots(), noOp(ErrandCachePort.class));

        assertEquals(1, useCase.recover(120, 20));
        assertTrue(reservations.rolledBack);
        assertEquals(ErrorCode.RUSH_RESERVATION_TIMEOUT, transaction.errorCode);
        assertNotNull(reservations.cached);
        assertEquals(RushRequestStatus.DEAD, reservations.cached.status());
    }

    @Test
    void failed_database_terminal_still_retries_redis_compensation() {
        Instant old = Instant.now().minusSeconds(300);
        RushReservationSnapshot stale = new RushReservationSnapshot(
                "rush:recovery:failed-1", 90001, 1, 1001,
                RushRequestStatus.RESERVED, "午餐代购", 100, old, old);
        FakeRequests requests = new FakeRequests();
        requests.value = new RushRequest(stale.requestId(), stale.campaignId(), stale.campusId(),
                stale.userId(), RushRequestStatus.FAILED_INSUFFICIENT_BALANCE,
                stale.title(), stale.rewardCents(), null, ErrorCode.INSUFFICIENT_BALANCE.name(),
                stale.acceptedAt(), old, old);
        FakeReservations reservations = new FakeReservations(stale);
        RushReservationRecoveryUseCase useCase = new RushReservationRecoveryUseCase(
                requests, reservations, new RecordingTransaction(requests),
                noOp(ErrandRepository.class), new NoOpSlots(), noOp(ErrandCachePort.class));

        assertEquals(0, useCase.recover(120, 20));
        assertTrue(reservations.rolledBack);
        assertSame(requests.value, reservations.cached);
    }

    private static final class RecordingTransaction extends RushOrderTransactionalStep {
        private final FakeRequests requests;
        private ErrorCode errorCode;

        RecordingTransaction(FakeRequests requests) {
            super(null, null, null, null, null);
            this.requests = requests;
        }

        @Override
        public void persistFailure(RushOrderMessage message, RushRequestStatus status, ErrorCode code) {
            errorCode = code;
            Instant now = Instant.now();
            requests.value = new RushRequest(message.requestId(), message.campaignId(), message.campusId(),
                    message.userId(), status, message.title(), message.rewardCents(), null,
                    code.name(), message.acceptedAt(), now, now);
        }
    }

    private static final class FakeRequests implements RushRequestRepository {
        private RushRequest value;
        @Override public Optional<RushRequest> findByRequestId(String id) { return Optional.ofNullable(value); }
        @Override public Optional<RushRequest> findByCampaignAndUser(long c, long u) { return Optional.empty(); }
        @Override public void insertReserved(RushOrderData data) {}
        @Override public void markSuccess(String requestId, long errandId) {}
        @Override public void saveFailure(RushOrderData data, RushRequestStatus status, String errorCode) {}
        @Override public void markFailure(String requestId, RushRequestStatus status, String errorCode) {}
        @Override public List<RushRequest> findStaleProcessing(Instant before, int limit) { return List.of(); }
        @Override public List<RushRequest> findSuccessfulSince(Instant since, int limit) { return List.of(); }
    }

    private static final class FakeReservations implements RushReservationPort {
        private final RushReservationSnapshot stale;
        private boolean rolledBack;
        private RushRequest cached;
        FakeReservations(RushReservationSnapshot stale) { this.stale = stale; }
        @Override public RushReservationOutcome reserve(long c,long u,String r,Instant n) { return RushReservationOutcome.RESERVED; }
        @Override public boolean rollback(long c,long u,String r,RushRequestStatus s) { rolledBack = true; return true; }
        @Override public void initialize(long c,int q) {}
        @Override public void cacheQueued(RushOrderMessage message) {}
        @Override public void cacheResult(RushRequest request) { cached = request; }
        @Override public Optional<RushRequest> findCached(String requestId) { return Optional.empty(); }
        @Override public List<RushReservationSnapshot> findStaleProcessing(Instant before,int limit) { return List.of(stale); }
        @Override public long remainingStock(long campaignId) { return -1; }
        @Override public long participantCount(long campaignId) { return 0; }
    }

    private static final class NoOpSlots implements GrabSlotPort {
        @Override public SlotOutcome tryAcquire(long e,long r,String q) { return SlotOutcome.NOT_GRABBABLE; }
        @Override public void rollback(long e,long r,String q) {}
        @Override public void initSlot(long e,int total,long ttl) {}
        @Override public long remainingSlot(long e) { return -1; }
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
