package dev.skyisland;

import com.google.gson.JsonObject;
import java.util.Set;

/** Command notation translates to capabilities; never dispatch to a console. */
final class VanillaCommands {
    static String root(String command) {
        if (command == null || command.length() > 2048 || command.isBlank() || command.startsWith("/")
            || command.chars().anyMatch(Character::isISOControl) || command.contains("{") || command.contains("[")
            || command.contains("@") || command.contains("~") || command.contains("^"))
            throw new IllegalArgumentException("只接受单行明确目标命令，不接受选择器、NBT 或相对坐标");
        String root = command.trim().split("\\s+")[0];
        if (!root.matches("[a-z_]+")) throw new IllegalArgumentException("命令根或命名空间无效");
        return root;
    }
    static JsonObject translate(JsonObject source) {
        String command = WorldActions.string(source, "command");
        String root = root(command); String[] p = command.trim().split("\\s+");
        JsonObject a = source.deepCopy(); a.remove("command");
        switch (root) {
            case "time" -> { need(p, 3); if (!Set.of("set", "add", "query").contains(p[1])) bad();
                a.addProperty("type", p[1].equals("query") ? "world_query" : p[1].equals("add") ? "add_time" : "set_time");
                if (p[1].equals("query")) { if (!Set.of("day", "daytime", "gametime").contains(p[2])) bad(); a.addProperty("query", "time"); }
                else a.addProperty("ticks", Long.parseLong(p[2])); }
            case "weather" -> { need(p, 2); if (!Set.of("clear", "rain", "thunder").contains(p[1])) bad();
                a.addProperty("type", "set_weather"); a.addProperty("storm", !p[1].equals("clear")); a.addProperty("thunder", p[1].equals("thunder")); }
            case "gamerule" -> { need(p, 3); a.addProperty("type", "set_gamerule"); a.addProperty("rule", p[1]);
                if (p[1].equals("randomTickSpeed")) a.addProperty("value", Integer.parseInt(p[2]));
                else { if (!Set.of("true", "false").contains(p[2])) bad(); a.addProperty("value", Boolean.parseBoolean(p[2])); } }
            case "worldborder" -> { need(p, 3); if (!p[1].equals("set")) bad(); a.addProperty("type", "set_border"); a.addProperty("size", Double.parseDouble(p[2])); }
            case "tp", "teleport" -> { need(p, 5); a.addProperty("type", "teleport"); a.addProperty("player", p[1]); coords(a, p, 2, ""); }
            case "summon" -> { need(p, 5); a.addProperty("type", "spawn_entity"); a.addProperty("entity", material(p[1])); a.addProperty("count", 1); coords(a, p, 2, ""); }
            case "kill" -> { need(p, 2); java.util.UUID.fromString(p[1]); a.addProperty("type", "remove_entity"); a.addProperty("uuid", p[1]); }
            case "setblock" -> { need(p, 5); a.addProperty("type", "set_blocks"); coords(a, p, 1, "1"); coords(a, p, 1, "2"); a.addProperty("material", material(p[4])); }
            case "fill" -> { need(p, 8); a.addProperty("type", "set_blocks"); coords(a, p, 1, "1"); coords(a, p, 4, "2"); a.addProperty("material", material(p[7])); }
            case "give" -> { need(p, 4); a.addProperty("type", "give_item"); a.addProperty("player", p[1]); a.addProperty("material", material(p[2])); a.addProperty("count", Integer.parseInt(p[3])); }
            case "clear" -> { need(p, 4); a.addProperty("type", "confiscate_item"); a.addProperty("player", p[1]); a.addProperty("material", material(p[2])); a.addProperty("count", Integer.parseInt(p[3])); }
            case "effect" -> { need(p, 6); if (!p[1].equals("give")) bad(); a.addProperty("type", "set_effect"); a.addProperty("player", p[2]); a.addProperty("effect", material(p[3])); a.addProperty("seconds", Integer.parseInt(p[4])); a.addProperty("amplifier", Integer.parseInt(p[5])); }
            case "gamemode" -> { need(p, 3); a.addProperty("type", "set_player_mode"); a.addProperty("mode", material(p[1])); a.addProperty("player", p[2]); }
            default -> throw new IllegalArgumentException("命令涉及世界外权限或未实现；请使用公布的世界工具");
        }
        return a;
    }
    private static String material(String value) {
        if (value.startsWith("minecraft:")) value = value.substring(10);
        if (!value.matches("[A-Za-z_]+")) throw new IllegalArgumentException("类型无效");
        return value.toUpperCase(java.util.Locale.ROOT);
    }
    private static void coords(JsonObject a, String[] p, int start, String suffix) {
        for (int i = 0; i < 3; i++) a.addProperty(new String[]{"x", "y", "z"}[i] + suffix, Double.parseDouble(p[start + i]));
    }
    private static void need(String[] p, int count) { if (p.length != count) throw new IllegalArgumentException("命令参数数量错误，请查工具说明"); }
    private static void bad() { throw new IllegalArgumentException("命令形式未支持，请查工具说明"); }
    static String scopeFor(AgentRole role) {
        return "命令模式=world-autonomous；命令符号转为结构化世界工具，仍遵守职责。可用公布的 time/weather/gamerule/worldborder/tp/summon/kill/fill/setblock/give/clear/effect/gamemode 形式，无控制台权限。";
    }
}
