package com.campusdash.domain.campaign.ports;

import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.model.RushRequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RushRequestRepository {
    Optional<RushRequest> findByRequestId(String requestId);
    Optional<RushRequest> findByCampaignAndUser(long campaignId, long userId);
    void insertReserved(RushOrderData data);
    void markSuccess(String requestId, long errandId);
    void saveFailure(RushOrderData data, RushRequestStatus status, String errorCode);
    void markFailure(String requestId, RushRequestStatus status, String errorCode);
    List<RushRequest> findStaleProcessing(Instant before, int limit);
    List<RushRequest> findSuccessfulSince(Instant since, int limit);

    record RushOrderData(String requestId, long campaignId, long campusId, long userId,
                         String title, long rewardCents, Instant acceptedAt) {}
}
