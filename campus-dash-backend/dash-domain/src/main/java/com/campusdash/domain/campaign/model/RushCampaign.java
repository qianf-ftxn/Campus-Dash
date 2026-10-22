package com.campusdash.domain.campaign.model;

import java.time.Instant;

public record RushCampaign(long id, long campusId, String name, Instant startAt, Instant endAt,
                           int quotaTotal, int soldCount, RushCampaignStatus status, long version) {

    public RushCampaign {
        if (endAt == null || startAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("活动结束时间必须晚于开始时间");
        }
        if (quotaTotal <= 0 || soldCount < 0 || soldCount > quotaTotal) {
            throw new IllegalArgumentException("活动名额不合法");
        }
    }

    public DisplayStatus displayStatus(Instant now) {
        if (status == RushCampaignStatus.DISABLED) return DisplayStatus.DISABLED;
        if (now.isBefore(startAt)) return DisplayStatus.NOT_STARTED;
        if (!now.isBefore(endAt)) return DisplayStatus.ENDED;
        return DisplayStatus.ACTIVE;
    }

    public boolean canSubmit(Instant now) {
        return displayStatus(now) == DisplayStatus.ACTIVE && soldCount < quotaTotal;
    }

    public int quotaRemaining() {
        return quotaTotal - soldCount;
    }

    public enum DisplayStatus {
        NOT_STARTED, ACTIVE, ENDED, DISABLED
    }
}
