package dev.skyisland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V060PolicyTest {
    @TempDir Path folder;
    @Test void safeTeleportRejectsDamageAndPortalMaterials() {
        for(org.bukkit.Material material:new org.bukkit.Material[]{org.bukkit.Material.MAGMA_BLOCK,org.bukkit.Material.CAMPFIRE,
            org.bukkit.Material.CACTUS,org.bukkit.Material.POWDER_SNOW,org.bukkit.Material.NETHER_PORTAL,org.bukkit.Material.LAVA})
            assertTrue(WorldActions.harmfulLanding(material));
        assertFalse(WorldActions.harmfulLanding(org.bukkit.Material.STONE));
        assertFalse(WorldActions.harmfulLanding(org.bukkit.Material.AIR));
    }

    @Test void vanillaCommandRouteRejectsNamespaceAndMultipleLines() {
        assertEquals("op", VanillaCommands.root("op Alex"));
        assertEquals("stop", VanillaCommands.root("stop"));
        assertThrows(IllegalArgumentException.class, () -> VanillaCommands.root("/op Alex"));
        assertThrows(IllegalArgumentException.class, () -> VanillaCommands.root("plugin:reload"));
        assertThrows(IllegalArgumentException.class, () -> VanillaCommands.root("time query day\nstop"));
        for(String command:new String[]{"op Alex","stop","execute run op Alex","function minecraft:test","reload","plugin:reload","kill @a","summon command_block_minecart 0 64 0 {Command:stop}"})
            assertThrows(IllegalArgumentException.class,()->VanillaCommands.translate(com.google.gson.JsonParser.parseString("{\"command\":\""+command+"\"}").getAsJsonObject()));
        assertEquals("set_time",VanillaCommands.translate(com.google.gson.JsonParser.parseString("{\"command\":\"time set 1000\",\"world\":\"world\"}").getAsJsonObject()).get("type").getAsString());
    }

    @Test void backupGateRejectsUnrelatedFileAndAcceptsInspectableWorldArchive() throws Exception {
        Path unrelated = folder.resolve("backup.txt");
        Files.writeString(unrelated, "not a backup");
        assertFalse(BackupVerifier.recent(folder));
        Path invalidZip = folder.resolve("bad.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(invalidZip))) {
            out.putNextEntry(new ZipEntry("notes.txt"));
            out.write("hello".getBytes());
            out.closeEntry();
        }
        assertFalse(BackupVerifier.recent(folder));
        Path valid = folder.resolve("world.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(valid))) {
            for (String name : new String[]{"world/level.dat", "world/region/r.0.0.mca"}) {
                out.putNextEntry(new ZipEntry(name));
                out.write(new byte[]{1, 2, 3});
                out.closeEntry();
            }
        }
        assertTrue(BackupVerifier.recent(folder));
        assertFalse(BackupVerifier.recent(valid, Instant.now().plusSeconds(60)));
    }

    @Test void replyCanRequestOneReadOnlyQueryButCannotMixItWithAnAction() {
        assertTrue(AgentReply.parse("{\"query\":{\"type\":\"entity_hotspots\"}}").validFormat());
        assertFalse(AgentReply.parse("{\"query\":{\"type\":\"entity_hotspots\"},"
            + "\"action\":{\"type\":\"set_time\"}}").validFormat());
    }
}
