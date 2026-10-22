package com.campusdash.domain.campaign.model;

public enum RushReservationOutcome {
    RESERVED(1),
    RESERVED_BY_SAME_REQUEST(2),
    SOLD_OUT(0),
    DUPLICATE_USER(-1),
    DUPLICATE_REQUEST_TERMINAL(-2),
    CAMPAIGN_NOT_INITIALIZED(-3);

    private final long code;

    RushReservationOutcome(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public static RushReservationOutcome fromCode(long code) {
        for (RushReservationOutcome value : values()) {
            if (value.code == code) return value;
        }
        throw new IllegalArgumentException("未知活动预占返回码: " + code);
    }
}
