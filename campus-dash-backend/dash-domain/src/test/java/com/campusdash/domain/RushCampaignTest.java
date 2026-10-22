package com.campusdash.domain;

import com.campusdash.domain.campaign.model.RushCampaign;
import com.campusdash.domain.campaign.model.RushCampaignStatus;
import com.campusdash.domain.campaign.model.RushRequestStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RushCampaignTest {
    private static final Instant START = Instant.parse("2026-09-22T03:30:00Z");
    private static final Instant END = Instant.parse("2026-09-22T03:35:00Z");

    @Test
    void derives_window_without_mutating_database_status() {
        RushCampaign c = new RushCampaign(1, 1, "午餐", START, END,
                500, 12, RushCampaignStatus.ENABLED, 0);
        assertEquals(RushCampaign.DisplayStatus.NOT_STARTED, c.displayStatus(START.minusMillis(1)));
        assertEquals(RushCampaign.DisplayStatus.ACTIVE, c.displayStatus(START));
        assertEquals(RushCampaign.DisplayStatus.ENDED, c.displayStatus(END));
        assertTrue(c.canSubmit(START.plusSeconds(1)));
        assertEquals(488, c.quotaRemaining());
    }

    @Test
    void disabled_and_sold_out_campaigns_cannot_submit() {
        RushCampaign disabled = new RushCampaign(1, 1, "午餐", START, END,
                500, 0, RushCampaignStatus.DISABLED, 0);
        RushCampaign soldOut = new RushCampaign(2, 1, "午餐", START, END,
                500, 500, RushCampaignStatus.ENABLED, 0);
        assertEquals(RushCampaign.DisplayStatus.DISABLED, disabled.displayStatus(START));
        assertFalse(disabled.canSubmit(START));
        assertFalse(soldOut.canSubmit(START));
    }

    @Test
    void request_terminal_and_public_status_are_explicit() {
        assertFalse(RushRequestStatus.RESERVED.terminal());
        assertEquals("PROCESSING", RushRequestStatus.QUEUED.publicStatus());
        assertEquals("SUCCESS", RushRequestStatus.SUCCESS.publicStatus());
        assertEquals("FAILED", RushRequestStatus.FAILED_SOLD_OUT.publicStatus());
        assertTrue(RushRequestStatus.DEAD.terminal());
    }
}
