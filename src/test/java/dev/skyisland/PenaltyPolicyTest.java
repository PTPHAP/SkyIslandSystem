package dev.skyisland;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PenaltyPolicyTest {
    @Test void distinguishesHighRiskFromFirstAndRepeatedRateIncidents() {
        long now = 1_700_000_000_000L;
        assertTrue(PenaltyPolicy.temporaryBan("tnt", 0, now));
        assertTrue(PenaltyPolicy.temporaryBan("spawn-egg", 0, now));
        assertFalse(PenaltyPolicy.temporaryBan("place", 0, now));
        assertFalse(PenaltyPolicy.temporaryBan("break", now - 86_400_000L, now));
        assertTrue(PenaltyPolicy.temporaryBan("place", now - 60_000L, now));
        assertTrue(PenaltyPolicy.temporaryBan("command", now - 60_000L, now));
    }
}
