package dev.skyisland;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OneLifeSeasonTest {
    @TempDir Path folder;

    @Test void announcedSeasonStartsAfter24HoursAndRestoresEliminatedPlayersAfterRestart() {
        long now = 1_700_000_000_000L;
        UUID player = UUID.randomUUID();
        OneLifeSeason season = new OneLifeSeason(folder);
        var action = JsonParser.parseString("""
            {"type":"schedule_season","delay_hours":24,"reason":"守护新世界"}
            """).getAsJsonObject();
        season.schedule(action, now);
        assertNull(season.activateDue(now + 23 * 3_600_000L));
        assertEquals(1, new OneLifeSeason(folder).activateDue(now + 24 * 3_600_000L).number());
        season = new OneLifeSeason(folder);
        assertTrue(season.recordDeath(player));
        assertFalse(season.recordDeath(player));
        assertTrue(new OneLifeSeason(folder).eliminated(player));
        season.schedule(action, now + 25 * 3_600_000L);
        OneLifeSeason.Start next = new OneLifeSeason(folder).activateDue(now + 49 * 3_600_000L);
        assertEquals(2, next.number());
        assertTrue(next.restored().contains(player));
        season = new OneLifeSeason(folder);
        assertFalse(season.eliminated(player));
        assertTrue(season.restorationNeeded(player));
        season.markRestored(player);
        assertFalse(new OneLifeSeason(folder).restorationNeeded(player));
    }

    @Test void rejectsUnannouncedOrDuplicateSeason() {
        OneLifeSeason season = new OneLifeSeason(folder);
        var action = JsonParser.parseString("""
            {"type":"schedule_season","delay_hours":23,"reason":"太快"}
            """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> season.schedule(action, 1_000));
        action.addProperty("delay_hours", 24.5);
        assertThrows(IllegalArgumentException.class, () -> season.schedule(action, 1_000));
        action.addProperty("delay_hours", "24");
        assertThrows(IllegalArgumentException.class, () -> season.schedule(action, 1_000));
        action.addProperty("delay_hours", 24);
        season.schedule(action, 1_000);
        assertThrows(IllegalArgumentException.class, () -> season.schedule(action, 2_000));
    }
}
