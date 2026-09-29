package dev.skyisland;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WindowCounterTest {
    @Test void countsOnlyTheConfiguredWindowAndTripsAtTheNextEvent() {
        WindowCounter counter = new WindowCounter();
        assertEquals(1, counter.add(1000, 30_000));
        assertEquals(2, counter.add(2000, 30_000));
        assertEquals(3, counter.add(3000, 30_000));
        assertEquals(1, counter.add(33_001, 30_000));
    }
}
