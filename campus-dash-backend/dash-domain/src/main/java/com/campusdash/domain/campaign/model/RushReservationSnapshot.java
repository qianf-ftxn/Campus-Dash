package com.campusdash.domain.campaign.model;

import java.time.Instant;

/** Redis中的活动请求快照，用于恢复跨Redis/MySQL事务边界留下的孤儿预占。 */
public record RushReservationSnapshot(
        String requestId,
        long campaignId,
        long campusId,
        long userId,
        RushRequestStatus status,
        String title,
        long rewardCents,
        Instant acceptedAt,
        Instant reservedAt
) {
    public boolean hasMessageMetadata() {
        return campaignId > 0 && campusId > 0 && userId > 0 && rewardCents > 0
                && title != null && !title.isBlank() && acceptedAt != null;
    }

    public RushOrderMessage toMessage() {
        return new RushOrderMessage(RushOrderMessage.CURRENT_SCHEMA_VERSION, requestId,
                campaignId, campusId, userId, acceptedAt, title, rewardCents);
    }
}
