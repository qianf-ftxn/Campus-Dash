package com.campusdash.infrastructure.config;

import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.campusdash.domain.campaign.ports.RushOrderQueuePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RocketMqRushOrderAdapter implements RushOrderQueuePort {
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    public RocketMqRushOrderAdapter(ClientServiceProvider provider, Producer producer,
                                    ObjectMapper objectMapper,
                                    @Value("${dash.mq.topic.rush-order:rush-order-request}") String topic) {
        this.provider = provider;
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void enqueue(RushOrderMessage request) {
        try {
            Message message = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("RUSH_ORDER")
                    .setKeys(request.requestId())
                    .setBody(objectMapper.writeValueAsBytes(request))
                    .build();
            producer.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("活动订单入队失败 requestId=" + request.requestId(), e);
        }
    }
}
