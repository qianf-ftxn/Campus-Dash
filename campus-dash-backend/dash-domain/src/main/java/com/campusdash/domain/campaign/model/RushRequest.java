package com.campusdash.domain.campaign.model;

import java.time.Instant;

public record RushRequest(String requestId, long campaignId, long campusId, long userId,
                          RushRequestStatus status, String title, long rewardCents,
                          Long errandId, String errorCode, Instant acceptedAt,
                          Instant createdAt, Instant updatedAt) {

    public boolean terminal() {
        return status.terminal();
    }
}
