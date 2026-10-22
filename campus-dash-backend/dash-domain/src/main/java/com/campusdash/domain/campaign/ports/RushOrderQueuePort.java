package com.campusdash.domain.campaign.ports;

import com.campusdash.domain.campaign.model.RushOrderMessage;

public interface RushOrderQueuePort {
    void enqueue(RushOrderMessage message);
}
