package com.campusdash.worker;

import com.campusdash.application.usecase.ProcessRushOrderUseCase;
import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RushOrderConsumerTest {

    @Test
    void malformed_json_is_acknowledged_as_poison_instead_of_retried() {
        RushOrderConsumer consumer = new RushOrderConsumer(null, null, new ObjectMapper(),
                new NeverCalledProcess(), "rush-order-request", "test-group", 1);

        assertEquals(ConsumeResult.SUCCESS, consumer.consume("{not-json".getBytes()));
    }

    private static final class NeverCalledProcess extends ProcessRushOrderUseCase {
        private NeverCalledProcess() { super(null, null, null, null, null, null, null); }
        @Override public Outcome process(RushOrderMessage message) {
            throw new AssertionError("malformed JSON must not reach application processing");
        }
    }
}
