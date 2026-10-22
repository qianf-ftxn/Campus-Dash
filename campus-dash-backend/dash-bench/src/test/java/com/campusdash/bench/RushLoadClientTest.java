package com.campusdash.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RushLoadClientTest {
    @Test void classifies_business_outcomes() {
        assertEquals("SOLD_OUT", RushLoadClient.classifyCode("RUSH_SOLD_OUT"));
        assertEquals("DUPLICATE", RushLoadClient.classifyCode("RUSH_DUPLICATE_USER"));
        assertEquals("INSUFFICIENT", RushLoadClient.classifyCode("INSUFFICIENT_BALANCE"));
        assertEquals("ERROR", RushLoadClient.classifyCode("INTERNAL_ERROR"));
    }
}
