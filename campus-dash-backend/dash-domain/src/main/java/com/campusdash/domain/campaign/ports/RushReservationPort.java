package com.campusdash.domain.campaign.ports;

import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.model.RushRequestStatus;
import com.campusdash.domain.campaign.model.RushReservationOutcome;
import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.campusdash.domain.campaign.model.RushReservationSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RushReservationPort {
    RushReservationOutcome reserve(long campaignId, long userId, String requestId, Instant now);
    boolean rollback(long campaignId, long userId, String requestId, RushRequestStatus failedStatus);
    void initialize(long campaignId, int quotaRemaining);
    void cacheQueued(RushOrderMessage message);
    void cacheResult(RushRequest request);
    Optional<RushRequest> findCached(String requestId);
    List<RushReservationSnapshot> findStaleProcessing(Instant before, int limit);
    long remainingStock(long campaignId);
    long participantCount(long campaignId);
}
