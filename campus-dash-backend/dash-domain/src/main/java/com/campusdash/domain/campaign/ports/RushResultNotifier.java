package com.campusdash.domain.campaign.ports;

import com.campusdash.domain.campaign.model.RushRequest;

public interface RushResultNotifier {
    void notifyResult(RushRequest request);
}
