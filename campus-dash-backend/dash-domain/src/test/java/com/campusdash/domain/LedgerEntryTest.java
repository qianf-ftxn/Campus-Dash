package com.campusdash.domain;

import com.campusdash.domain.wallet.model.LedgerEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LedgerEntryTest {
    @Test
    void rushBizNoIsDeterministicDistinctAndFitsDatabaseColumn() {
        String requestId = "r".repeat(64);
        String first = LedgerEntry.rushEscrowBizNo(requestId);

        assertEquals(first, LedgerEntry.rushEscrowBizNo(requestId));
        assertNotEquals(first, LedgerEntry.rushEscrowBizNo("x" + requestId.substring(1)));
        assertTrue(first.startsWith("rush:escrow:"));
        assertTrue(first.length() <= 64);
    }
}
