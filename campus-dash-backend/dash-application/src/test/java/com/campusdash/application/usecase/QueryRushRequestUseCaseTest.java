package com.campusdash.application.usecase;

import com.campusdash.application.usecase.query.QueryRushRequestUseCase;
import com.campusdash.domain.campaign.model.*;
import com.campusdash.domain.campaign.ports.RushRequestRepository;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryRushRequestUseCaseTest {
    @Test
    void pollingReadsRedisBeforeDatabase() {
        Instant now = Instant.parse("2026-09-22T09:00:00Z");
        RushRequest cached = new RushRequest("request-1", 90001, 1, 1001,
                RushRequestStatus.SUCCESS, "午餐", 100, 80001L, null, now, now, now);
        RushReservationPort reservations = new CachedReservations(cached);
        RushRequestRepository databaseMustNotBeRead = new EmptyDatabase() {
            @Override public Optional<RushRequest> findByRequestId(String requestId) {
                throw new AssertionError("缓存命中时不应回源MySQL");
            }
        };

        var view = new QueryRushRequestUseCase(databaseMustNotBeRead, reservations)
                .query("request-1", 1001);

        assertEquals("SUCCESS", view.status());
        assertEquals(80001L, view.errandId());
    }

    private static final class CachedReservations implements RushReservationPort {
        private final RushRequest cached;
        private CachedReservations(RushRequest cached) { this.cached = cached; }
        @Override public Optional<RushRequest> findCached(String requestId) { return Optional.of(cached); }
        @Override public RushReservationOutcome reserve(long c,long u,String r,Instant n) { throw new AssertionError(); }
        @Override public boolean rollback(long c,long u,String r,RushRequestStatus s) { throw new AssertionError(); }
        @Override public void initialize(long c,int q) { throw new AssertionError(); }
        @Override public void cacheQueued(RushOrderMessage message) { throw new AssertionError(); }
        @Override public void cacheResult(RushRequest request) { throw new AssertionError(); }
        @Override public List<RushReservationSnapshot> findStaleProcessing(Instant b,int l) { return List.of(); }
        @Override public long remainingStock(long campaignId) { return -1; }
        @Override public long participantCount(long campaignId) { return 0; }
    }

    private static class EmptyDatabase implements RushRequestRepository {
        @Override public Optional<RushRequest> findByRequestId(String id) { return Optional.empty(); }
        @Override public Optional<RushRequest> findByCampaignAndUser(long c,long u) { return Optional.empty(); }
        @Override public void insertReserved(RushOrderData data) { throw new AssertionError(); }
        @Override public void markSuccess(String requestId,long errandId) { throw new AssertionError(); }
        @Override public void saveFailure(RushOrderData data,RushRequestStatus status,String error) { throw new AssertionError(); }
        @Override public void markFailure(String requestId,RushRequestStatus status,String error) { throw new AssertionError(); }
        @Override public List<RushRequest> findStaleProcessing(Instant before,int limit) { return List.of(); }
        @Override public List<RushRequest> findSuccessfulSince(Instant since,int limit) { return List.of(); }
    }
}
