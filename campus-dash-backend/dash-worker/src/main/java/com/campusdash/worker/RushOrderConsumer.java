package com.campusdash.worker;

import com.campusdash.application.usecase.ProcessRushOrderUseCase;
import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RushOrderConsumer {
    private static final Logger log = LoggerFactory.getLogger(RushOrderConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final ObjectMapper objectMapper;
    private final ProcessRushOrderUseCase process;
    private final String topic;
    private final String group;
    private final int threads;
    private PushConsumer consumer;

    public RushOrderConsumer(ClientServiceProvider provider, ClientConfiguration configuration,
                             ObjectMapper objectMapper, ProcessRushOrderUseCase process,
                             @Value("${dash.mq.topic.rush-order:rush-order-request}") String topic,
                             @Value("${dash.mq.group.rush-order:dash-rush-order-consumer}") String group,
                             @Value("${dash.mq.consumer.rush-order-threads:8}") int threads) {
        this.provider = provider;
        this.configuration = configuration;
        this.objectMapper = objectMapper;
        this.process = process;
        this.topic = topic;
        this.group = group;
        this.threads = threads;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setConsumptionThreadCount(threads)
                .setSubscriptionExpressions(Collections.singletonMap(
                        topic, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(view -> consume(toBytes(view)))
                .build();
        log.info("活动订单消费者已启动 topic={} group={} threads={}", topic, group, threads);
    }

    ConsumeResult consume(byte[] body) {
        try {
            RushOrderMessage message = objectMapper.readValue(body, RushOrderMessage.class);
            ProcessRushOrderUseCase.Outcome outcome = process.process(message);
            return outcome == ProcessRushOrderUseCase.Outcome.RETRY
                    ? ConsumeResult.FAILURE : ConsumeResult.SUCCESS;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("活动订单毒消息，确认丢弃", e);
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("活动订单消费暂时失败，等待重试", e);
            return ConsumeResult.FAILURE;
        }
    }

    private static byte[] toBytes(org.apache.rocketmq.client.apis.message.MessageView view) {
        var buffer = view.getBody();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) consumer.close();
    }
}
