package com.campusdash.domain.campaign.ports;

import com.campusdash.domain.campaign.model.RushCampaign;

import java.util.List;
import java.util.Optional;

public interface RushCampaignRepository {
    Optional<RushCampaign> findById(long campaignId);
    List<RushCampaign> findByCampus(long campusId);
    int casIncrementSold(long campaignId);
    long countSuccessfulRequests(long campaignId);
}
