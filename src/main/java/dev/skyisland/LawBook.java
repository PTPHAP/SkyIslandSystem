package dev.skyisland;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/** The executable part of Phanes's laws. AI text never becomes a command. */
final class LawBook {
    private static final long NOTICE_MILLIS = 5 * 60_000L;
    private static final Map<String, Rule> DEFAULTS = Map.of(
        "place", new Rule(600, 60, 30, 0),
        "break", new Rule(900, 60, 30, 0),
        "tnt", new Rule(32, 30, 30, 0),
        "spawn-egg", new Rule(64, 30, 30, 0),
        "command", new Rule(120, 30, 30, 0));
    private final Path file;
    private final Map<String, Rule> active = new LinkedHashMap<>(DEFAULTS);
    private final Map<String, Scheduled> pending = new LinkedHashMap<>();
    private long version;
    private String planTitle = "守护世界稳定";
    private String planGoal = "巡查风险、保护玩家与世界，并保留每次裁决的证据。";

    record Rule(int limit, int windowSeconds, int banMinutes, long version) {}
    private record Scheduled(Rule rule, long effectiveAt, String reason) {}

    LawBook(Path folder) {
        file = folder.resolve("laws.properties");
        if (!Files.exists(file)) return;
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            data.load(in);
            version = Long.parseLong(data.getProperty("version", "0"));
            planTitle = data.getProperty("plan.title", planTitle);
            planGoal = data.getProperty("plan.goal", planGoal);
            validateText(planTitle, 60);
            validateText(planGoal, 300);
            for (String signal : DEFAULTS.keySet()) {
                String prefix = signal + ".";
                if (data.containsKey(prefix + "limit")) active.put(signal, new Rule(
                    Integer.parseInt(data.getProperty(prefix + "limit")),
                    Integer.parseInt(data.getProperty(prefix + "window")),
                    Integer.parseInt(data.getProperty(prefix + "ban")),
                    Long.parseLong(data.getProperty(prefix + "version"))));
                validateRule(signal, active.get(signal));
                if (data.containsKey(prefix + "pending.limit")) pending.put(signal, new Scheduled(new Rule(
                    Integer.parseInt(data.getProperty(prefix + "pending.limit")),
                    Integer.parseInt(data.getProperty(prefix + "pending.window")),
                    Integer.parseInt(data.getProperty(prefix + "pending.ban")),
                    Long.parseLong(data.getProperty(prefix + "pending.version"))),
                    Long.parseLong(data.getProperty(prefix + "pending.at")),
                    data.getProperty(prefix + "pending.reason", "")));
                if (pending.containsKey(signal)) validateRule(signal, pending.get(signal).rule);
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("法令文件损坏；请先从备份恢复 " + file, invalid);
        }
    }

    Rule rule(String signal) { return active.get(signal); }

    String summary() {
        StringBuilder out = new StringBuilder("神圣规划版本 " + version + "：" + planTitle + "；" + planGoal);
        active.forEach((signal, rule) -> out.append("\n").append(signal).append(": ")
            .append(rule.limit).append("/").append(rule.windowSeconds).append("秒, 临封")
            .append(rule.banMinutes).append("分钟"));
        pending.forEach((signal, scheduled) -> out.append("\n待生效 ").append(signal)
            .append(" @ ").append(Instant.ofEpochMilli(scheduled.effectiveAt)));
        return out.toString();
    }

    String publicSummary() {
        StringBuilder out = new StringBuilder("法涅斯神圣规划 v" + version
            + "：" + planTitle + "。" + planGoal
            + "\n禁止高频破坏方块、密集 TNT、滥用刷怪蛋和命令洪泛。违规行为将留证，身份已验证者可被临时封禁，最长 24 小时。");
        pending.forEach((signal, scheduled) -> out.append("\n待生效：").append(signal)
            .append("，").append(scheduled.reason).append("，时间 ")
            .append(Instant.ofEpochMilli(scheduled.effectiveAt)));
        return out.toString();
    }

    void validate(JsonObject action) {
        String signal = required(action, "signal");
        if (!DEFAULTS.containsKey(signal)) throw new IllegalArgumentException("未知防护信号");
        Rule proposed = new Rule(action.get("limit").getAsInt(), action.get("window_seconds").getAsInt(),
            action.get("ban_minutes").getAsInt(), version + 1);
        validateRule(signal, proposed);
        String reason = required(action, "reason");
        if (reason.isBlank() || reason.length() > 120 || reason.indexOf('§') >= 0
            || reason.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("法令理由须为 1-120 字的单行文字");
        if (action.has("emergency") && !action.get("emergency").getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("emergency 必须是布尔值");
    }

    private static void validateRule(String signal, Rule rule) {
        int minimum = switch (signal) {
            case "place" -> 120;
            case "break" -> 180;
            case "tnt" -> 8;
            case "spawn-egg" -> 16;
            default -> 30;
        };
        if (rule.limit < minimum || rule.limit > 10_000 || rule.windowSeconds < 10
            || rule.windowSeconds > 3_600 || rule.banMinutes < 1 || rule.banMinutes > 1_440)
            throw new IllegalArgumentException("法令超出可执行范围");
    }

    void validatePlan(JsonObject action) {
        validateText(required(action, "title"), 60);
        validateText(required(action, "goal"), 300);
    }

    String declarePlan(JsonObject action, Consumer<String> audit) {
        validatePlan(action);
        if (planTitle.equals(required(action, "title")) && planGoal.equals(required(action, "goal")))
            return "规划内容未变化";
        String previousTitle = planTitle;
        String previousGoal = planGoal;
        planTitle = required(action, "title");
        planGoal = required(action, "goal");
        version++;
        try { save(); }
        catch (RuntimeException failure) {
            planTitle = previousTitle;
            planGoal = previousGoal;
            version--;
            throw failure;
        }
        audit.accept("plan-declared version=" + version + " title=" + planTitle + " goal=" + planGoal);
        return "法涅斯公布神圣规划 v" + version + "：《" + planTitle + "》 " + planGoal;
    }

    private static void validateText(String value, int max) {
        if (value.isBlank() || value.length() > max || value.indexOf('§') >= 0
            || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("规划文字须为单行且不超过 " + max + " 字");
    }

    String apply(JsonObject action, boolean recentIncident, Consumer<String> audit) {
        validate(action);
        String signal = required(action, "signal");
        String reason = required(action, "reason");
        boolean emergency = action.has("emergency") && action.get("emergency").getAsBoolean();
        if (emergency && !recentIncident) throw new IllegalArgumentException("没有近期防护事件，不能跳过公告期");
        Rule current = active.get(signal);
        Scheduled scheduled = pending.get(signal);
        Rule requested = new Rule(action.get("limit").getAsInt(), action.get("window_seconds").getAsInt(),
            action.get("ban_minutes").getAsInt(), version + 1);
        if (scheduled == null && sameLimits(current, requested)
            || !emergency && scheduled != null && sameLimits(scheduled.rule, requested))
            return "法令内容未变化";
        Rule rule = new Rule(action.get("limit").getAsInt(), action.get("window_seconds").getAsInt(),
            action.get("ban_minutes").getAsInt(), ++version);
        Rule previous = active.get(signal);
        Scheduled previouslyPending = pending.get(signal);
        if (emergency) {
            active.put(signal, rule);
            pending.remove(signal);
        } else pending.put(signal, new Scheduled(rule, System.currentTimeMillis() + NOTICE_MILLIS, reason));
        try { save(); }
        catch (RuntimeException failure) {
            active.put(signal, previous);
            if (previouslyPending == null) pending.remove(signal); else pending.put(signal, previouslyPending);
            version--;
            throw failure;
        }
        audit.accept("law-" + (emergency ? "effective" : "scheduled") + " version=" + version
            + " signal=" + signal + " limit=" + rule.limit + " window=" + rule.windowSeconds
            + " ban-minutes=" + rule.banMinutes + " reason=" + reason);
        return "法涅斯法令 v" + version + "：" + signal + "，" + reason
            + (emergency ? "（立即生效）" : "（5 分钟后生效）");
    }

    private static boolean sameLimits(Rule left, Rule right) {
        return left.limit == right.limit && left.windowSeconds == right.windowSeconds
            && left.banMinutes == right.banMinutes;
    }

    void activateDue(Consumer<String> announce, Consumer<String> audit) {
        long now = System.currentTimeMillis();
        for (String signal : new ArrayList<>(pending.keySet())) {
            Scheduled scheduled = pending.get(signal);
            if (scheduled.effectiveAt > now) continue;
            Rule previous = active.put(signal, scheduled.rule);
            pending.remove(signal);
            try { save(); }
            catch (RuntimeException failure) {
                active.put(signal, previous);
                pending.put(signal, scheduled);
                throw failure;
            }
            String line = "法涅斯法令 v" + scheduled.rule.version + " 已生效："
                + signal + "，" + scheduled.reason;
            audit.accept("law-effective version=" + scheduled.rule.version + " signal=" + signal);
            announce.accept(line);
        }
    }

    private void save() {
        Properties data = new Properties();
        data.setProperty("version", Long.toString(version));
        data.setProperty("plan.title", planTitle);
        data.setProperty("plan.goal", planGoal);
        active.forEach((signal, rule) -> writeRule(data, signal + ".", rule));
        pending.forEach((signal, scheduled) -> {
            writeRule(data, signal + ".pending.", scheduled.rule);
            data.setProperty(signal + ".pending.at", Long.toString(scheduled.effectiveAt));
            data.setProperty(signal + ".pending.reason", scheduled.reason);
        });
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            data.store(out, "SkyIslandSystem laws");
        } catch (Exception error) { throw new IllegalStateException("无法保存法令", error); }
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception error) { throw new IllegalStateException("无法保存法令", error); }
        } catch (Exception error) { throw new IllegalStateException("无法保存法令", error); }
    }

    private static void writeRule(Properties data, String prefix, Rule rule) {
        data.setProperty(prefix + "limit", Integer.toString(rule.limit));
        data.setProperty(prefix + "window", Integer.toString(rule.windowSeconds));
        data.setProperty(prefix + "ban", Integer.toString(rule.banMinutes));
        data.setProperty(prefix + "version", Long.toString(rule.version));
    }

    private static String required(JsonObject action, String key) {
        if (!action.has(key) || !action.get(key).isJsonPrimitive())
            throw new IllegalArgumentException("缺少字段 " + key);
        return action.get(key).getAsString();
    }
}
