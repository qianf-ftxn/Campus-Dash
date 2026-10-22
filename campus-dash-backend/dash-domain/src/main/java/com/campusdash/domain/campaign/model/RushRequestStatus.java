package com.campusdash.domain.campaign.model;

public enum RushRequestStatus {
    QUEUED,
    RESERVED,
    SUCCESS,
    FAILED_SOLD_OUT,
    FAILED_DUPLICATE,
    FAILED_INSUFFICIENT_BALANCE,
    FAILED_INVALID,
    DEAD;

    public boolean terminal() {
        return this == SUCCESS || name().startsWith("FAILED_") || this == DEAD;
    }

    public String publicStatus() {
        if (this == QUEUED || this == RESERVED) return "PROCESSING";
        if (this == SUCCESS) return "SUCCESS";
        return "FAILED";
    }
}
