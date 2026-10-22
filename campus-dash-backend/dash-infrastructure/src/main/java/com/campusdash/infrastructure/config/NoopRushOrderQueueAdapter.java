package com.campusdash.infrastructure.config;

import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.campusdash.domain.campaign.ports.RushOrderQueuePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopRushOrderQueueAdapter implements RushOrderQueuePort {
    @Override
    public void enqueue(RushOrderMessage message) {
        throw new IllegalStateException("RocketMQ未启用，活动请求禁止同步降级");
    }
}
