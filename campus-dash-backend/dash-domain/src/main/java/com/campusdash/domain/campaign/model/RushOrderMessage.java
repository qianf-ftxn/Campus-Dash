package com.campusdash.domain.campaign.model;

import java.time.Instant;

public record RushOrderMessage(int schemaVersion, String requestId, long campaignId,
                               long campusId, long userId, Instant acceptedAt,
                               String title, long rewardCents) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RushOrderMessage {
        if (requestId == null || requestId.isBlank() || acceptedAt == null || title == null || title.isBlank()) {
            throw new IllegalArgumentException("活动订单消息缺少必填字段");
        }
        if (campaignId <= 0 || campusId <= 0 || userId <= 0 || rewardCents <= 0) {
            throw new IllegalArgumentException("活动订单消息数值字段不合法");
        }
    }

    public boolean supportedSchema() {
        return schemaVersion == CURRENT_SCHEMA_VERSION;
    }
}
