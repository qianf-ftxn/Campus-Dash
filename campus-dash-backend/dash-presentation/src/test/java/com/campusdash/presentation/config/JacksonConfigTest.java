package com.campusdash.presentation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonConfigTest {

    @Test
    void keepsJavaTimeModuleWhenInstallingLongSerializer() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        Sample sample = new Sample(9_007_199_254_740_993L,
                Instant.parse("2026-09-22T04:00:00Z"));
        String json = mapper.writeValueAsString(sample);

        assertTrue(json.contains("\"id\":\"9007199254740993\""));
        assertEquals(sample, mapper.readValue(json, Sample.class));
    }

    private record Sample(long id, Instant at) {}
}
