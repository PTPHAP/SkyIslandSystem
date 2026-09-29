package dev.skyisland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GovernanceTest {
    @TempDir Path folder;

    @Test void lawsRequireEvidenceForImmediateChangeAndSurviveRestart() {
        LawBook laws = new LawBook(folder);
        var action = JsonParser.parseString("""
            {"type":"set_law","signal":"tnt","limit":16,"window_seconds":30,
             "ban_minutes":90,"reason":"TNT 密集放置风险","emergency":true}
            """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> laws.apply(action, false, ignored -> {}));
        assertEquals(32, laws.rule("tnt").limit());
        laws.apply(action, true, ignored -> {});
        LawBook reloaded = new LawBook(folder);
        assertEquals(16, reloaded.rule("tnt").limit());
        assertEquals(90, reloaded.rule("tnt").banMinutes());
        assertFalse(reloaded.publicSummary().contains("limit=16"));
        var plan = JsonParser.parseString("""
            {"type":"declare_plan","title":"守护天空岛","goal":"优先保护玩家聚居地并调查异常行为"}
            """).getAsJsonObject();
        reloaded.declarePlan(plan, ignored -> {});
        assertTrue(new LawBook(folder).publicSummary().contains("守护天空岛"));
        action.addProperty("ban_minutes", 1_441);
        assertThrows(IllegalArgumentException.class, () -> reloaded.apply(action, true, ignored -> {}));
    }

    @Test void shadowScopeAndSuspensionPersistButPhanesCanPardon() {
        ShadowDiscipline discipline = new ShadowDiscipline(folder);
        assertTrue(discipline.check(AgentRole.RONOVA, "set_border").startsWith("越界"));
        discipline.violate(AgentRole.RONOVA, "越界权能 set_border");
        assertEquals("", discipline.check(AgentRole.RONOVA, "remove_entity"));
        discipline.violate(AgentRole.RONOVA, "越界权能 set_border");
        ShadowDiscipline reloaded = new ShadowDiscipline(folder);
        assertTrue(reloaded.check(AgentRole.RONOVA, "remove_entity").startsWith("权能暂停"));
        reloaded.pardon("ronova");
        assertEquals("", new ShadowDiscipline(folder).check(AgentRole.RONOVA, "remove_entity"));
        var grant = JsonParser.parseString("""
            {"type":"set_shadow_scope","role":"ronova","action_type":"set_border","allowed":true}
            """).getAsJsonObject();
        reloaded.setScope(grant);
        assertEquals("", new ShadowDiscipline(folder).check(AgentRole.RONOVA, "set_border"));
    }

    @Test void longWindowCannotTurnNormalBuildingIntoAutomaticBan() {
        LawBook laws = new LawBook(folder);
        var action = JsonParser.parseString("""
            {"type":"set_law","signal":"place","limit":120,"window_seconds":3600,
             "ban_minutes":30,"reason":"过低阈值","emergency":false}
            """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> laws.validate(action));
    }

    @Test void lawCooldownAndThresholdFeedbackSurviveRestart() {
        LawBook laws = new LawBook(folder);
        var first = JsonParser.parseString("""
            {"type":"set_law","signal":"tnt","limit":24,"window_seconds":30,
             "ban_minutes":30,"reason":"紧急风险","emergency":true}
            """).getAsJsonObject();
        laws.apply(first, true, ignored -> {});
        var changed = first.deepCopy();
        changed.addProperty("limit", 28);
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> new LawBook(folder).validate(changed)).getMessage().contains("冷却"));
        var invalid = first.deepCopy();
        invalid.addProperty("limit", 1);
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> laws.validate(invalid)).getMessage().contains("至少 16"));
        assertTrue(laws.constraints().contains("place=300"));
    }

    @Test void existingUnsafeLawIsBackedUpAndResetOnUpgrade() throws Exception {
        Files.writeString(folder.resolve("laws.properties"), """
            version=1
            place.limit=120
            place.window=3600
            place.ban=30
            place.version=1
            """);
        LawBook laws = new LawBook(folder);
        assertTrue(laws.adjustedUnsafeRules());
        assertEquals(600, laws.rule("place").limit());
        assertTrue(Files.exists(folder.resolve("laws.pre-v0.3.0.properties")));
        assertFalse(new LawBook(folder).adjustedUnsafeRules());
    }
}
