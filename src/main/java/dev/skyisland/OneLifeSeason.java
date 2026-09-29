package dev.skyisland;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** One shared life across dimensions; player identity must be authenticated by Paper. */
final class OneLifeSeason {
    private final Path file;
    private final Set<UUID> eliminated = new HashSet<>();
    private final Set<UUID> restore = new HashSet<>();
    private int number;
    private boolean active;
    private long startsAt;
    private String reason = "";

    record Start(int number, Set<UUID> restored) {}

    OneLifeSeason(Path folder) {
        file = folder.resolve("one-life.properties");
        if (!Files.exists(file)) return;
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            data.load(in);
            number = Integer.parseInt(data.getProperty("number", "0"));
            String state = data.getProperty("active", "false");
            if (!state.equals("true") && !state.equals("false")) throw new IllegalArgumentException("active 无效");
            active = Boolean.parseBoolean(state);
            startsAt = Long.parseLong(data.getProperty("starts-at", "0"));
            reason = data.getProperty("reason", "");
            if (number < 0 || startsAt < 0) throw new IllegalArgumentException("赛季状态无效");
            for (String key : data.stringPropertyNames())
                if (key.startsWith("dead.")) eliminated.add(UUID.fromString(key.substring(5)));
                else if (key.startsWith("restore.")) restore.add(UUID.fromString(key.substring(8)));
        } catch (Exception invalid) {
            throw new IllegalStateException("一命赛季记录损坏；请从备份恢复 " + file, invalid);
        }
    }

    void validate(JsonObject action) {
        if (!action.has("delay_hours") || !action.get("delay_hours").isJsonPrimitive()
            || !action.get("delay_hours").getAsJsonPrimitive().isNumber()
            || !action.get("delay_hours").getAsString().matches("[0-9]{1,3}"))
            throw new IllegalArgumentException("缺少 delay_hours");
        int hours = action.get("delay_hours").getAsInt();
        if (hours < 24 || hours > 168) throw new IllegalArgumentException("新赛季须提前 24 至 168 小时公告");
        String text = reason(action);
        if (text.isBlank() || text.length() > 120 || text.indexOf('§') >= 0
            || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("赛季理由须为 1-120 字的单行文字");
    }

    String schedule(JsonObject action, long now) {
        validate(action);
        if (startsAt != 0) throw new IllegalArgumentException("已有待开始赛季，不可重复改期");
        startsAt = now + action.get("delay_hours").getAsInt() * 3_600_000L;
        reason = reason(action);
        try { save(); }
        catch (RuntimeException failure) { startsAt = 0; reason = ""; throw failure; }
        return "法涅斯宣布一命赛季 " + (number + 1) + " 将于 " + Instant.ofEpochMilli(startsAt)
            + " 开始；" + reason;
    }

    Start activateDue(long now) {
        if (startsAt == 0 || now < startsAt) return null;
        Set<UUID> previous = Set.copyOf(eliminated);
        Set<UUID> previousRestore = Set.copyOf(restore);
        long previousStart = startsAt;
        int previousNumber = number;
        boolean previousActive = active;
        String previousReason = reason;
        restore.addAll(eliminated);
        eliminated.clear();
        number++;
        active = true;
        startsAt = 0;
        reason = "";
        try { save(); }
        catch (RuntimeException failure) {
            eliminated.addAll(previous);
            restore.clear();
            restore.addAll(previousRestore);
            number = previousNumber;
            active = previousActive;
            startsAt = previousStart;
            reason = previousReason;
            throw failure;
        }
        return new Start(number, previous);
    }

    boolean recordDeath(UUID player) {
        if (!active || !eliminated.add(player)) return false;
        try { save(); }
        catch (RuntimeException failure) { eliminated.remove(player); throw failure; }
        return true;
    }

    boolean eliminated(UUID player) { return active && eliminated.contains(player); }

    boolean restorationNeeded(UUID player) { return restore.contains(player); }

    void markRestored(UUID player) {
        if (!restore.remove(player)) return;
        try { save(); }
        catch (RuntimeException failure) { restore.add(player); throw failure; }
    }

    String summary() {
        return "一命赛季 " + (active ? number + " 进行中，已失去资格 " + eliminated.size() + " 人" : "尚未开始")
            + (startsAt == 0 ? "" : "；下赛季于 " + Instant.ofEpochMilli(startsAt) + " 开始：" + reason);
    }

    private static String reason(JsonObject action) {
        if (!action.has("reason") || !action.get("reason").isJsonPrimitive())
            throw new IllegalArgumentException("缺少 reason");
        return action.get("reason").getAsString();
    }

    private void save() {
        Properties data = new Properties();
        data.setProperty("number", Integer.toString(number));
        data.setProperty("active", Boolean.toString(active));
        data.setProperty("starts-at", Long.toString(startsAt));
        data.setProperty("reason", reason);
        for (UUID id : eliminated) data.setProperty("dead." + id, "true");
        for (UUID id : restore) data.setProperty("restore." + id, "true");
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) { data.store(out, "SkyIslandSystem one-life season"); }
        catch (Exception error) { throw new IllegalStateException("一命赛季记录写入失败", error); }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception error) { throw new IllegalStateException("一命赛季记录保存失败", error); }
        } catch (Exception error) { throw new IllegalStateException("一命赛季记录保存失败", error); }
    }
}
