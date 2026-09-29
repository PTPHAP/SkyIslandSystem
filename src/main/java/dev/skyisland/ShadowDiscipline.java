package dev.skyisland;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Independent proposals are permitted only within a shadow's current mandate. */
final class ShadowDiscipline {
    private static final long DAY = 86_400_000L;
    private static final Set<String> TYPES = Set.of("set_time", "set_weather", "set_gamerule",
        "set_border", "teleport", "spawn_entity", "remove_entity", "set_blocks", "set_law");
    private final Path file;
    private final Map<AgentRole, State> states = new EnumMap<>(AgentRole.class);

    private static final class State {
        Set<String> allowed;
        int violations;
        long windowStart;
        long suspendedUntil;
        State(Set<String> allowed) { this.allowed = new HashSet<>(allowed); }
    }

    ShadowDiscipline(Path folder) {
        file = folder.resolve("shadow-discipline.properties");
        states.put(AgentRole.RONOVA, new State(Set.of("remove_entity", "set_law")));
        states.put(AgentRole.NABERIUS, new State(Set.of("spawn_entity", "set_gamerule", "set_law")));
        states.put(AgentRole.ISTAROTH, new State(Set.of("set_time", "set_weather", "set_gamerule", "set_law")));
        states.put(AgentRole.ASMODAY, new State(Set.of("set_border", "teleport", "set_law")));
        if (!Files.exists(file)) return;
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            data.load(in);
            for (var entry : states.entrySet()) {
                String prefix = entry.getKey().id + ".";
                State state = entry.getValue();
                if (data.containsKey(prefix + "scope")) {
                    String scopes = data.getProperty(prefix + "scope");
                    state.allowed = scopes.isBlank() ? new HashSet<>() : new HashSet<>(Arrays.asList(scopes.split(",")));
                    if (!TYPES.containsAll(state.allowed)) throw new IllegalArgumentException("非法权能范围");
                }
                state.violations = Integer.parseInt(data.getProperty(prefix + "violations", "0"));
                state.windowStart = Long.parseLong(data.getProperty(prefix + "window-start", "0"));
                state.suspendedUntil = Long.parseLong(data.getProperty(prefix + "suspended-until", "0"));
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("四影权能记录损坏；请从备份恢复 " + file, invalid);
        }
    }

    String check(AgentRole role, String type) {
        State state = states.get(role);
        if (state == null) return "仅四影受此权能约束";
        if (state.suspendedUntil > System.currentTimeMillis())
            return "权能暂停至 " + Instant.ofEpochMilli(state.suspendedUntil);
        if (!state.allowed.contains(type)) return "越界权能 " + type;
        return "";
    }

    String violate(AgentRole role, String reason) {
        State state = states.get(role);
        if (state == null) throw new IllegalArgumentException("仅四影可记违令");
        long now = System.currentTimeMillis();
        if (state.windowStart == 0 || now - state.windowStart >= DAY) {
            state.windowStart = now;
            state.violations = 0;
        }
        state.violations++;
        long duration = switch (state.violations) {
            case 1 -> 0;
            case 2 -> 10 * 60_000L;
            case 3 -> 60 * 60_000L;
            default -> DAY;
        };
        state.suspendedUntil = Math.max(state.suspendedUntil, now + duration);
        save();
        return role.display + " 第 " + state.violations + " 次违令：" + reason
            + (duration == 0 ? "；已警告" : "；权能暂停至 " + Instant.ofEpochMilli(state.suspendedUntil));
    }

    void validateScope(JsonObject action) {
        AgentRole role = AgentRole.parse(required(action, "role"));
        String type = required(action, "action_type");
        if (role == AgentRole.PHANES || !TYPES.contains(type)) throw new IllegalArgumentException("无法调整此权能");
        if (!action.has("allowed") || !action.get("allowed").getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("allowed 必须是布尔值");
    }

    String setScope(JsonObject action) {
        validateScope(action);
        AgentRole role = AgentRole.parse(required(action, "role"));
        String type = required(action, "action_type");
        boolean allowed = action.get("allowed").getAsBoolean();
        State state = states.get(role);
        boolean previouslyAllowed = state.allowed.contains(type);
        if (allowed) state.allowed.add(type); else state.allowed.remove(type);
        try { save(); }
        catch (RuntimeException failure) {
            if (previouslyAllowed) state.allowed.add(type); else state.allowed.remove(type);
            throw failure;
        }
        return "法涅斯已" + (allowed ? "授予" : "收回") + role.display + " 的 " + type + " 权能";
    }

    String pardon(String roleName) {
        AgentRole role = AgentRole.parse(roleName);
        State state = states.get(role);
        if (state == null) throw new IllegalArgumentException("无法赦免此角色");
        int previousViolations = state.violations;
        long previousWindowStart = state.windowStart;
        long previousSuspension = state.suspendedUntil;
        state.violations = 0;
        state.windowStart = 0;
        state.suspendedUntil = 0;
        try { save(); }
        catch (RuntimeException failure) {
            state.violations = previousViolations;
            state.windowStart = previousWindowStart;
            state.suspendedUntil = previousSuspension;
            throw failure;
        }
        return "法涅斯已解除" + role.display + " 的权能暂停";
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        states.forEach((role, state) -> out.append("\n").append(role.display)
            .append(" 权能=").append(state.allowed)
            .append(state.suspendedUntil > System.currentTimeMillis()
                ? " 暂停至 " + Instant.ofEpochMilli(state.suspendedUntil) : ""));
        return out.toString();
    }

    private void save() {
        Properties data = new Properties();
        states.forEach((role, state) -> {
            String prefix = role.id + ".";
            data.setProperty(prefix + "scope", String.join(",", state.allowed));
            data.setProperty(prefix + "violations", Integer.toString(state.violations));
            data.setProperty(prefix + "window-start", Long.toString(state.windowStart));
            data.setProperty(prefix + "suspended-until", Long.toString(state.suspendedUntil));
        });
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) { data.store(out, "SkyIslandSystem shadow discipline"); }
        catch (Exception error) { throw new IllegalStateException("无法保存四影权能", error); }
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception error) { throw new IllegalStateException("无法保存四影权能", error); }
        } catch (Exception error) { throw new IllegalStateException("无法保存四影权能", error); }
    }

    private static String required(JsonObject action, String key) {
        if (!action.has(key) || !action.get(key).isJsonPrimitive())
            throw new IllegalArgumentException("缺少字段 " + key);
        return action.get(key).getAsString();
    }
}
