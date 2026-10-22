package com.campusdash.application.usecase.query;

import com.campusdash.domain.campaign.model.RushCampaign;
import com.campusdash.domain.campaign.ports.RushCampaignRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class QueryRushCampaignUseCase {
    private final RushCampaignRepository campaigns;
    private final Clock clock;

    @Autowired
    public QueryRushCampaignUseCase(RushCampaignRepository campaigns) {
        this(campaigns, Clock.systemUTC());
    }

    QueryRushCampaignUseCase(RushCampaignRepository campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    public List<View> list(long campusId) {
        Instant now = clock.instant();
        return campaigns.findByCampus(campusId).stream().map(c -> view(c, now)).toList();
    }

    public View detail(long campaignId) {
        RushCampaign campaign = campaigns.findById(campaignId)
                .orElseThrow(() -> new BizException(ErrorCode.RUSH_CAMPAIGN_NOT_FOUND));
        return view(campaign, clock.instant());
    }

    private static View view(RushCampaign c, Instant now) {
        return new View(c.id(), c.campusId(), c.name(), c.startAt(), c.endAt(), c.quotaTotal(),
                c.soldCount(), c.displayStatus(now).name(), c.canSubmit(now));
    }

    public record View(long id, long campusId, String name, Instant startAt, Instant endAt,
                       int quotaTotal, int soldCount, String status, boolean canSubmit) {}
}
