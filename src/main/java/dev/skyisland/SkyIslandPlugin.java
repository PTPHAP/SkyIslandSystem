package dev.skyisland;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkyIslandPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final String TITLE = ChatColor.DARK_PURPLE + "天空岛体系 · 天理议事厅";
    private final Map<AgentRole, String> replies = new HashMap<>();
    private final Map<UUID, String> recentDeaths = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Long> rejectedProposals = new HashMap<>();
    private final Map<AgentRole, String> lastFeedback = new HashMap<>();
    private final Deque<String> recentAudit = new ArrayDeque<>();
    private final ExecutorService auditIo = Executors.newSingleThreadExecutor();
    private OpenClawClient client;
    private WorldActions actions;
    private AbuseGuard guard;
    private LawBook laws;
    private OneLifeSeason season;
    private OfflineAuthGate auth;
    private ShadowDiscipline discipline;
    private boolean paused;
    private String gatewayState = "未验证";
    private String gatewayDetail = "";
    private String alert = "正常";
    private final Map<String, Long> lastIncidentReview = new HashMap<>();
    private long lastTimeAlert;
    private int lowTpsSamples;
    private boolean meetingBusy;
    private String lastMeeting = "尚未召开";

    private record Pending(AgentRole from, WorldActions.Prepared action, String hash, long expiresAt) {}

    private void prunePending() {
        rejectedProposals.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending proposal = iterator.next();
            if (proposal.expiresAt() >= System.currentTimeMillis()) continue;
            iterator.remove();
            audit("proposal-expired id=" + proposal.action.id());
            if (!proposal.hash.equals("admin")) rejectProposal(proposal, "上次同一提案超时，30 分钟内不要原样重提");
            notifyAdmins("提案 " + proposal.action.id() + " 已超时，动作未执行");
        }
    }

    private void notifyAdmins(String message) {
        Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("skyisland.admin"))
            .forEach(p -> p.sendMessage(ChatColor.GOLD + "天空岛：" + message));
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        File secret = new File(getDataFolder(), "secrets.yml");
        String token = YamlConfiguration.loadConfiguration(secret).getString("gateway-token", "");
        if (token.isBlank()) token = System.getenv().getOrDefault("SKYISLAND_OPENCLAW_TOKEN", "");
        int requestedTimeout = getConfig().getInt("request-timeout-seconds", 210);
        if (requestedTimeout < 180) getLogger().warning("OpenClaw 请求超时低于 180 秒；本次提升至 180 秒以容纳模型冷启动");
        client = new OpenClawClient(getConfig().getString("gateway-url", "http://127.0.0.1:19789"),
            token, Math.max(180, requestedTimeout));
        if (!client.configured()) gatewayState = "未配置";
        actions = new WorldActions(this);
        laws = new LawBook(getDataFolder().toPath());
        if (laws.adjustedUnsafeRules()) getLogger().warning("旧法令的高频阈值过低，已备份原文件并恢复安全默认值");
        season = new OneLifeSeason(getDataFolder().toPath());
        if (!getServer().getOnlineMode()) {
            auth = new OfflineAuthGate(this);
            if (!auth.available()) getLogger().severe("身份记录损坏：离线玩家将被拒绝登录；请恢复 identities.properties");
            else getLogger().warning("离线模式：已启用天空岛注册/登录；旧存档须控制台认领");
        }
        discipline = new ShadowDiscipline(getDataFolder().toPath());
        guard = new AbuseGuard(this, laws);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(guard, this);
        if (auth != null) Bukkit.getPluginManager().registerEvents(auth, this);
        getCommand("skyisland").setExecutor(this);
        getCommand("skyisland").setTabCompleter(this);
        long period = Math.max(60, getConfig().getLong("review-interval-seconds", 600)) * 20;
        Bukkit.getScheduler().runTaskTimer(this, this::review, 20 * 60, period);
        long meetingPeriod = Math.max(1, getConfig().getLong("meeting-interval-hours", 24)) * 20L * 3600;
        Bukkit.getScheduler().runTaskTimer(this, this::startMeeting, 20L * 60 * 5, meetingPeriod);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            prunePending();
            if (!paused) {
                try { laws.activateDue(message -> announce(message, "法令已生效"), this::audit); }
                catch (RuntimeException failure) { getLogger().severe("法令生效失败: " + failure.getMessage()); }
            }
        }, 20 * 20, 20 * 20);
        Bukkit.getScheduler().runTaskTimer(this, this::checkSeason, 1, 20 * 20);
        Bukkit.getScheduler().runTaskTimer(this, this::checkTickHealth, 20 * 20, 20 * 20);
        getLogger().info("天空岛体系已启动；OpenClaw " + (client.configured() ? "已配置" : "等待凭证"));
    }

    @Override public void onDisable() {
        if (actions != null) actions.close();
        if (guard != null) guard.close();
        if (auth != null) auth.close();
        auditIo.shutdown();
    }

    private void review() {
        prunePending();
        double tps = Bukkit.getTPS()[0];
        long disk = getDataFolder().getUsableSpace();
        alert = tps < 18 || disk < 5_000_000_000L ? "需要检查：TPS 或磁盘空间" : "正常";
        if (paused || !client.configured()) return;
        if (gatewayState.equals("请求失败")) {
            ask(AgentRole.PHANES, "连接恢复检查。只返回一句简短状态，不提出动作。", null);
            return;
        }
        String metrics = metrics() + "\n" + laws.summary() + "\n" + season.summary()
            + "\n" + guard.riskSummary();
        for (AgentRole role : AgentRole.values())
            ask(role, "定期巡查。主动寻找本领域有证据支持且尚未处理的问题；确有必要时提出一个符合权能的动作。没有必要动作时 action 为 null。"
                + (role == AgentRole.PHANES ? "可自主决定是否提前公告下一轮一命赛季。" : "")
                + "\n" + metrics, null);
    }

    private void startMeeting() {
        if (meetingBusy || paused || !client.configured()) return;
        meetingBusy = true;
        String id = UUID.randomUUID().toString().substring(0, 8);
        StringBuilder minutes = new StringBuilder("会议 " + id + "。世界摘要：\n" + metrics() + laws.summary());
        audit("meeting-open id=" + id);
        discuss(AgentRole.PHANES, "召开天空岛会议。请提出本次世界治理议题；只讨论，不提交动作。\n" + minutes,
            opening -> {
                minutes.append("\n法涅斯议题：").append(opening);
                discussShadow(0, minutes, id);
            });
    }

    private void discussShadow(int index, StringBuilder minutes, String id) {
        AgentRole[] shadows = {AgentRole.RONOVA, AgentRole.NABERIUS, AgentRole.ISTAROTH, AgentRole.ASMODAY};
        if (index == shadows.length) {
            discuss(AgentRole.PHANES, "天空岛会议闭幕。请依据四执政意见作出简短结论；如需行动，下一次巡查再提出受限动作。\n" + minutes,
                conclusion -> {
                    lastMeeting = "会议 " + id + "：" + conclusion;
                    audit("meeting-close id=" + id + " conclusion=" + conclusion);
                    notifyAdmins(lastMeeting);
                    meetingBusy = false;
                });
            return;
        }
        AgentRole role = shadows[index];
        discuss(role, "天空岛会议。请就你的职责提出有证据的意见、异议或建议；本轮只讨论，action 必须为 null。\n" + minutes,
            opinion -> {
                minutes.append("\n").append(role.display).append("：").append(opinion);
                discussShadow(index + 1, minutes, id);
            });
    }

    private void discuss(AgentRole role, String prompt, java.util.function.Consumer<String> done) {
        client.ask(role, prompt + "\n本轮是会议发言，任何 action、approval、delegate 均不会执行。\n"
            + discipline.scopeFor(role)).whenComplete((reply, error) -> {
                if (!isEnabled()) return;
                Bukkit.getScheduler().runTask(this, () -> {
                    String opinion = error != null || reply == null || !reply.validFormat()
                        ? "本轮未能形成有效发言" : reply.message().replace('\n', ' ').replace('\r', ' ');
                    if (opinion.length() > 300) opinion = opinion.substring(0, 300);
                    audit("meeting-opinion role=" + role.id + " text=" + opinion);
                    done.accept(opinion);
                });
            });
    }

    private void checkSeason() {
        if (!identityReady()) return;
        try {
            OneLifeSeason.Start started = season.activateDue(System.currentTimeMillis());
            if (started == null) return;
            audit("one-life-season-start number=" + started.number());
            for (Player player : Bukkit.getOnlinePlayers())
                if (authenticated(player) && started.restored().contains(player.getUniqueId())) restorePlayer(player);
            announce("法涅斯开启一命赛季 " + started.number() + "；每位生存玩家只有一条命。", "新赛季已开始");
        } catch (RuntimeException failure) { getLogger().severe("一命赛季启动失败: " + failure.getMessage()); }
    }

    private void checkTickHealth() {
        double tps = Bukkit.getTPS()[0];
        lowTpsSamples = tps < 16 ? lowTpsSamples + 1 : 0;
        long now = System.currentTimeMillis();
        if (lowTpsSamples < 3 || now - lastTimeAlert < 600_000L) return;
        lastTimeAlert = now;
        audit("time-warning tps=" + String.format("%.2f", tps));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle("§b时之执政 · 伊斯塔露", "§7世界运转迟滞，正在巡查", 10, 55, 15);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 0.8f);
        }
        if (!paused && client.configured()) ask(AgentRole.ISTAROTH,
            "持续低 TPS 告警，依据聚合指标诊断；证据不足时不要提出动作。\n" + metrics(), null);
    }

    private void restorePlayer(Player player) {
        if (!season.restorationNeeded(player.getUniqueId())) return;
        try {
            if (!player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()))
                throw new IllegalStateException("出生点传送失败");
            player.setGameMode(GameMode.SURVIVAL);
            season.markRestored(player.getUniqueId());
            player.sendMessage("§a新赛季已开始，你的一命资格已恢复。");
        } catch (RuntimeException failure) {
            getLogger().severe("玩家一命资格恢复失败: " + player.getUniqueId() + " " + failure.getMessage());
            player.kickPlayer("天空岛资格恢复失败，请联系管理员检查服务器日志。");
        }
    }

    boolean authenticated(Player player) {
        return getServer().getOnlineMode() || auth != null && auth.verified(player);
    }

    private boolean identityReady() {
        return getServer().getOnlineMode() || auth != null && auth.available();
    }

    void onIdentityVerified(Player player) {
        if (guard.enforceExistingBan(player)) return;
        applySeasonIdentity(player);
    }

    private void applySeasonIdentity(Player player) {
        if (season.restorationNeeded(player.getUniqueId())) restorePlayer(player);
        else if (season.eliminated(player.getUniqueId())) {
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage("§5你的一命资格已在本赛季耗尽。" + season.summary());
        }
    }

    private String metrics() {
        StringBuilder out = new StringBuilder();
        out.append("TPS=").append(String.format("%.2f", Bukkit.getTPS()[0]))
            .append(", tick-ms=").append(String.format("%.2f", Bukkit.getAverageTickTime()))
            .append(", online=").append(Bukkit.getOnlinePlayers().size())
            .append(", free-disk-GiB=").append(getDataFolder().getUsableSpace() / (1024L * 1024 * 1024))
            .append(", backup-recent=").append(actions.recentBackup()).append('\n');
        for (World world : Bukkit.getWorlds()) out.append(world.getName())
            .append(": chunks=").append(world.getLoadedChunks().length)
            .append(", entities=").append(world.getEntityCount()).append('\n');
        int shown = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (++shown > 12) { out.append("更多玩家位置已省略\n"); break; }
            out.append("player=").append(player.getName()).append(" world=").append(player.getWorld().getName())
                .append(" x=").append(player.getLocation().getBlockX())
                .append(" y=").append(player.getLocation().getBlockY())
                .append(" z=").append(player.getLocation().getBlockZ())
                .append(" verified=").append(authenticated(player)).append('\n');
            int nearby = 0;
            for (Entity entity : player.getNearbyEntities(12, 8, 12)) {
                if (!(entity instanceof Monster || entity instanceof Item) || entity.getCustomName() != null) continue;
                if (++nearby > 3) break;
                out.append(" nearby=").append(entity.getType()).append(" uuid=").append(entity.getUniqueId())
                    .append(" x=").append(entity.getLocation().getBlockX())
                    .append(" y=").append(entity.getLocation().getBlockY())
                    .append(" z=").append(entity.getLocation().getBlockZ()).append('\n');
            }
        }
        return out.toString();
    }

    private void announce(String message, String subtitle) {
        announce(message, subtitle, AgentRole.PHANES);
    }

    private void announce(String message, String subtitle, AgentRole role) {
        Bukkit.broadcastMessage(ChatColor.GOLD + message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle("§d" + role.display, "§6" + subtitle, 10, 60, 15);
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.55f, 1.0f);
        }
    }

    void reviewIncident(String signal, String evidence) {
        long now = System.currentTimeMillis();
        boolean emergency = guard.severeIncident(signal);
        String reviewKey = signal + (emergency ? ":emergency" : ":ordinary");
        if (paused || !client.configured() || now - lastIncidentReview.getOrDefault(reviewKey, 0L) < 60_000L) return;
        lastIncidentReview.put(reviewKey, now);
        AgentRole shadow = switch (signal) {
            case "tnt" -> AgentRole.RONOVA;
            case "spawn-egg" -> AgentRole.NABERIUS;
            case "command" -> AgentRole.ISTAROTH;
            default -> AgentRole.ASMODAY;
        };
        String caseId = UUID.randomUUID().toString().substring(0, 8);
        int baseline = guard.incidentCount(signal);
        String context = "案件 " + caseId + "，信号=" + signal + "。原始证据：\n" + evidence
            + "\n" + metrics() + "\n" + laws.summary();
        audit("case-open id=" + caseId + " signal=" + signal + " evidence=" + evidence);
        if (emergency) {
            audit("case-emergency id=" + caseId + " role=" + shadow.id);
            ask(shadow, "严重高频事件已由插件留证。可自行提出与本信号一致的 emergency=true 法令，"
                + "无需等待法涅斯；其他动作仍需审批。不要把一次计数当作外挂证明。\n" + context,
                null, context, null, signal);
        } else ask(AgentRole.PHANES,
            "请先审理案件。若需要由本领域执政执行任务，返回 delegate={\"role\":\"" + shadow.id
                + "\"}；若证据不足，delegate 为 null。不要把一次高频计数当作外挂证明。\n" + context,
            null, "", (decision, ignored) -> {
                if (decision == null || !decision.validFormat() || decision.delegateRole().isBlank()) {
                    audit("case-closed id=" + caseId + " reason=no-delegation");
                    return;
                }
                if (!decision.delegateRole().equals(shadow.id)) {
                    audit("case-delegation-rejected id=" + caseId + " role=" + decision.delegateRole());
                    notifyAdmins("案件 " + caseId + " 委派角色与事件职责不符，未执行");
                    return;
                }
                audit("case-delegated id=" + caseId + " role=" + shadow.id);
                notifyAdmins("法涅斯已将案件 " + caseId + " 委派给 " + shadow.display);
                ask(shadow, "法涅斯已审理并委派你处理案件。请依据证据在自身权能内行动；"
                    + "如果需要世界动作，提交提案供法涅斯审批。\n裁决：" + decision.message()
                    + "\n" + context, null, context, null);
            });
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int repeated = guard.incidentCount(signal) - baseline;
            audit("case-followup id=" + caseId + " signal=" + signal + " additional-incidents=" + repeated);
            if (repeated > 0 && !paused && client.configured()) ask(AgentRole.PHANES,
                "案件 " + caseId + " 在五分钟后仍出现 " + repeated + " 次同类高频事件。"
                    + "请根据证据复查法令；不要越过插件边界。\n" + laws.summary(), null);
        }, 20L * 60 * 5);
    }

    private void ask(AgentRole role, String prompt, CommandSender sender) {
        ask(role, prompt, sender, "", null);
    }

    private void ask(AgentRole role, String prompt, CommandSender sender, String caseContext,
                     java.util.function.BiConsumer<AgentReply, Boolean> completed) {
        ask(role, prompt, sender, caseContext, completed, "");
    }

    private void ask(AgentRole role, String prompt, CommandSender sender, String caseContext,
                     java.util.function.BiConsumer<AgentReply, Boolean> completed, String emergencySignal) {
        if (!client.configured()) { if (sender != null) sender.sendMessage("OpenClaw 未配置"); return; }
        String feedback = lastFeedback.getOrDefault(role, "");
        String instructions = "\n当前权能：" + discipline.scopeFor(role) + "\n" + laws.constraints()
            + (feedback.isEmpty() ? "" : "\n上次提案被拒原因：" + feedback + "；请勿原样重提。")
            + "\n" + laws.summary();
        client.ask(role, prompt + instructions).whenComplete((reply, error) -> {
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                if (error != null) {
                    gatewayState = "请求失败";
                    Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                    String reason = cause instanceof java.net.ConnectException ? "连接被拒绝；检查专用 Gateway 是否在配置的回环端口运行"
                        : cause instanceof java.net.http.HttpTimeoutException ? "请求超时；检查 Gateway 与模型连接"
                        : cause instanceof IllegalStateException ? cause.getMessage()
                        : cause.getClass().getSimpleName() + "；检查专用实例日志";
                    String failure = "OpenClaw 请求失败: " + reason;
                    replies.put(role, failure);
                    gatewayDetail = reason;
                    getLogger().warning(role.id + " " + failure);
                    if (sender != null) sender.sendMessage(ChatColor.RED + failure);
                    if (completed != null) completed.accept(null, false);
                    return;
                }
                String message = reply.message().replace('\n', ' ').replace('\r', ' ');
                if (message.length() > 300) message = message.substring(0, 300) + "…";
                if (!reply.validFormat()) {
                    audit("invalid-reply role=" + role.id + " action-ignored");
                    notifyAdmins(role.display + " 回复不是有效 JSON，动作未执行；请检查角色输出格式");
                    if (sender != null) sender.sendMessage(ChatColor.RED + "角色回复格式无效，动作未执行：" + message);
                    if (completed != null) completed.accept(reply, false);
                    return;
                }
                replies.put(role, message);
                gatewayState = "已响应";
                gatewayDetail = "";
                if (sender != null) sender.sendMessage(ChatColor.LIGHT_PURPLE + role.display + ": " + message);
                boolean proposed = handleReply(role, reply, caseContext, emergencySignal);
                if (completed != null) completed.accept(reply, proposed);
            });
        });
    }

    @EventHandler public void death(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        String cause = player.getLastDamageCause() == null ? "未知" : player.getLastDamageCause().getCause().name();
        recentDeaths.put(player.getUniqueId(), cause);
        audit("player-death uuid=" + player.getUniqueId() + " cause=" + cause + " world=" + player.getWorld().getName());
        if (authenticated(player) && player.getGameMode() == GameMode.SURVIVAL) {
            try {
                if (season.recordDeath(player.getUniqueId()))
                    audit("one-life-eliminated season=" + season.summary() + " uuid=" + player.getUniqueId());
            } catch (RuntimeException failure) {
                getLogger().severe("一命死亡记录失败: " + failure.getMessage());
                player.kickPlayer("天空岛死亡记录失败，请联系管理员检查服务器日志。");
            }
        }
    }

    @EventHandler public void respawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String cause = recentDeaths.remove(player.getUniqueId());
        if (cause == null) return;
        if (season.eliminated(player.getUniqueId())) player.setGameMode(GameMode.SPECTATOR);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            boolean eliminated = season.eliminated(player.getUniqueId());
            if (eliminated) player.setGameMode(GameMode.SPECTATOR);
            player.sendTitle("§5死之执政 · 若娜瓦", eliminated ? "§7此赛季的生命已尽" : "§7死亡已记入天空岛的纪事", 10, 55, 15);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.7f);
            player.sendMessage("§7若娜瓦记录了你的死亡（原因：" + cause + "）。"
                + (eliminated ? "你将以旁观者身份等待下一赛季。" : "天空岛已将此事记入纪事。"));
        }, 2L);
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        if (!getServer().getOnlineMode()) return;
        applySeasonIdentity(event.getPlayer());
    }

    @EventHandler public void changedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        audit("player-world-change uuid=" + player.getUniqueId() + " from=" + event.getFrom().getName()
            + " to=" + player.getWorld().getName());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            player.sendTitle("§5空之执政 · 阿斯莫代", "§7维度边界已通过", 10, 45, 10);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.35f, 1.1f);
        }, 1L);
    }

    @EventHandler public void playerQuit(PlayerQuitEvent event) {
        recentDeaths.remove(event.getPlayer().getUniqueId());
    }

    private boolean handleReply(AgentRole role, AgentReply reply, String caseContext, String emergencySignal) {
        prunePending();
        if (role == AgentRole.PHANES && !reply.approvalId().isBlank()) {
            Pending p = pending.get(reply.approvalId());
            if (p == null || p.hash.equals("admin") || !p.hash.equals(reply.approvalHash())) {
                audit("approval-rejected invalid-id-or-hash id=" + reply.approvalId());
                notifyAdmins("法涅斯审批引用了无效提案 ID 或 HASH，动作未执行");
                return false;
            }
            pending.remove(reply.approvalId());
            audit("approval " + p.action.id() + " " + reply.approved()
                + " approver=phanes proposer=" + p.from.id);
            notifyAdmins("提案 " + p.action.id() + (reply.approved() ? " 已获法涅斯批准" : " 被法涅斯否决，动作未执行"));
            if (!reply.approved()) rejectProposal(p, "法涅斯否决了同一提案");
            if (reply.approved()) {
                String boundary = discipline.check(p.from, WorldActions.string(p.action.action(), "type"));
                if (boundary.isEmpty()) executeOrQueue(p.action());
                else {
                    rejectProposal(p, "审批时权能已变化：" + boundary);
                    audit("approval-rejected changed-shadow-scope id=" + p.action.id() + " reason=" + boundary);
                    notifyAdmins("提案 " + p.action.id() + " 审批后因权能变化被拒绝：" + boundary);
                }
            }
            return false;
        }
        if (reply.action() == null || paused) return false;
        String hash = sha256(reply.action().toString());
        String key = role.id + ":" + hash;
        if (role != AgentRole.PHANES && (rejectedProposals.getOrDefault(key, 0L) > System.currentTimeMillis()
            || pending.values().stream().anyMatch(p -> p.from == role && p.hash.equals(hash)))) {
            lastFeedback.put(role, "同一动作仍待审批或已在 30 分钟内被拒绝");
            audit("proposal-duplicate from=" + role.id + " hash=" + hash);
            notifyAdmins(role.display + " 的重复提案已抑制；30 分钟内不会再次送审");
            return false;
        }
        try {
            String type = WorldActions.string(reply.action(), "type");
            if (role != AgentRole.PHANES) {
                String boundary = discipline.check(role, type);
                if (!boundary.isEmpty()) {
                    if (boundary.startsWith("权能暂停")) audit("shadow-suspended from=" + role.id + " reason=" + boundary);
                    else audit("shadow-violation " + discipline.violate(role, boundary));
                    lastFeedback.put(role, boundary);
                    notifyAdmins(role.display + " 提案被边界拒绝：" + boundary);
                    return false;
                }
            }
            WorldActions.Prepared prepared = prepareAction(reply.action());
            if (role == AgentRole.PHANES) {
                audit("direct-action proposer=phanes id=" + prepared.id());
                executeOrQueue(prepared, role);
                return false;
            }
            if (!emergencySignal.isBlank() && type.equals("set_law")
                && emergencySignal.equals(WorldActions.string(reply.action(), "signal"))
                && reply.action().has("emergency") && reply.action().get("emergency").getAsBoolean()
                && guard.hasRecentIncident(emergencySignal)) {
                LawBook.Rule current = laws.rule(emergencySignal);
                int newLimit = reply.action().get("limit").getAsInt();
                int newWindow = reply.action().get("window_seconds").getAsInt();
                int newBan = reply.action().get("ban_minutes").getAsInt();
                if (newWindow != current.windowSeconds() || newLimit > current.limit()
                    || newBan < current.banMinutes() || newLimit == current.limit() && newBan == current.banMinutes())
                    throw new IllegalArgumentException("紧急直行只能在原时间窗口收紧阈值或延长临封；不得放宽或重复当前法令");
                audit("shadow-emergency-action id=" + prepared.id() + " role=" + role.id + " signal=" + emergencySignal);
                notifyAdmins(role.display + " 对 " + emergencySignal + " 严重事件已启动紧急法令");
                executeOrQueue(prepared, role);
                return false;
            }
            pending.put(prepared.id(), new Pending(role, prepared, hash, System.currentTimeMillis() + 300_000));
            lastFeedback.remove(role);
            audit("shadow-proposal " + prepared.id() + " from=" + role.id + " hash=" + hash);
            notifyAdmins(role.display + " 已提交提案 " + prepared.id() + "，等待法涅斯审批（5 分钟超时）");
            ask(AgentRole.PHANES, "影子 " + role.display + " 提议以下动作。你只能按世界治理规则审批，不能改写动作。"
                + (caseContext.isEmpty() ? "" : "\n" + caseContext + "\n影子意见（未经验证）：" + reply.message())
                + "\n提案 ID=" + prepared.id() + "\nHASH=" + hash + "\n动作=" + reply.action()
                + "\n只在同意时返回 approval={id,hash,approved:true}；否则 false。", null, "",
                (approval, ignored) -> {
                    Pending rejected = pending.remove(prepared.id());
                    if (rejected == null) return;
                    rejectProposal(rejected, "法涅斯未给出有效审批 ID/HASH");
                    audit("approval-rejected missing-or-invalid id=" + prepared.id());
                    notifyAdmins("提案 " + prepared.id() + " 未获有效审批，已拒绝；动作未执行");
                });
            return true;
        } catch (RuntimeException invalid) {
            if (role != AgentRole.PHANES)
                rejectedProposals.put(key, System.currentTimeMillis() + 30 * 60_000L);
            lastFeedback.put(role, invalid.getMessage() == null ? "动作校验异常" : invalid.getMessage());
            audit("invalid-proposal from=" + role.id + " reason=" + invalid.getMessage());
            notifyAdmins(role.display + " 提案未执行：" + actionableError(invalid.getMessage()));
            return false;
        }
    }

    private void rejectProposal(Pending proposal, String reason) {
        rejectedProposals.put(proposal.from.id + ":" + proposal.hash, System.currentTimeMillis() + 30 * 60_000L);
        lastFeedback.put(proposal.from, reason);
    }

    private WorldActions.Prepared prepareAction(JsonObject action) {
        String type = WorldActions.string(action, "type");
        switch (type) {
            case "set_law" -> laws.validate(action);
            case "schedule_season" -> {
                if (!identityReady()) throw new IllegalArgumentException("一命赛季要求可用的玩家身份验证");
                season.validate(action);
            }
            case "declare_plan" -> laws.validatePlan(action);
            case "set_shadow_scope" -> discipline.validateScope(action);
            case "pardon_shadow" -> {
                if (AgentRole.parse(WorldActions.string(action, "role")) == AgentRole.PHANES)
                    throw new IllegalArgumentException("不能赦免法涅斯");
            }
            default -> { return actions.prepare(action); }
        }
        return new WorldActions.Prepared(UUID.randomUUID().toString().substring(0, 8), action.deepCopy(), "");
    }

    private void executeOrQueue(WorldActions.Prepared prepared) {
        executeOrQueue(prepared, AgentRole.PHANES);
    }

    private void executeOrQueue(WorldActions.Prepared prepared, AgentRole issuer) {
        if (paused) return;
        if (!prepared.reason().isEmpty()) {
            pending.put(prepared.id(), new Pending(AgentRole.PHANES, prepared, "admin", System.currentTimeMillis() + 3_600_000));
            audit("admin-confirmation-required " + prepared.id() + " reason=" + prepared.reason());
            Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("skyisland.admin"))
                .forEach(p -> p.sendMessage(ChatColor.GOLD + "天空岛复杂编辑待确认 " + prepared.id()
                    + "：" + prepared.reason() + "。" + backupStatus()
                    + "；查看 /skyisland，确认 /skyisland confirm " + prepared.id()));
            return;
        }
        execute(prepared, issuer);
    }

    private void execute(WorldActions.Prepared prepared) {
        execute(prepared, AgentRole.PHANES);
    }

    private void execute(WorldActions.Prepared prepared, AgentRole issuer) {
        audit("action-start " + prepared.id() + " " + prepared.action());
        java.util.function.Consumer<String> report = result -> {
            audit("action-result " + prepared.id() + " " + result);
            notifyAdmins("动作 " + prepared.id() + "：" + (result.startsWith("动作拒绝: ")
                ? actionableError(result.substring("动作拒绝: ".length())) : result));
        };
        try {
            String type = WorldActions.string(prepared.action(), "type");
            switch (type) {
                case "set_law" -> {
                    String result = laws.apply(prepared.action(),
                        guard.hasRecentIncident(WorldActions.string(prepared.action(), "signal")), this::audit,
                        issuer == AgentRole.PHANES ? "法涅斯" : issuer.display);
                    if (result.contains("法令 v")) announce(result, "法令已宣告", issuer);
                    report.accept(result);
                }
                case "declare_plan" -> {
                    String result = laws.declarePlan(prepared.action(), this::audit);
                    if (result.startsWith("法涅斯公布")) announce(result, "神圣规划已公布");
                    report.accept(result);
                }
                case "schedule_season" -> {
                    if (!identityReady()) throw new IllegalArgumentException("一命赛季要求可用的玩家身份验证");
                    String result = season.schedule(prepared.action(), System.currentTimeMillis());
                    audit("one-life-season-scheduled " + result);
                    announce(result, "下一赛季已公告");
                    report.accept(result);
                }
                case "set_shadow_scope" -> report.accept(discipline.setScope(prepared.action()));
                case "pardon_shadow" -> report.accept(discipline.pardon(WorldActions.string(prepared.action(), "role")));
                default -> actions.execute(prepared, report);
            }
        } catch (RuntimeException invalid) { report.accept("动作拒绝: " + invalid.getMessage()); }
    }

    private String backupStatus() {
        return getConfig().getString("backup-directory", "").isBlank() ? "备份目录未配置，无法确认复杂编辑"
            : actions.recentBackup() ? "检测到近 24 小时备份文件（内容尚需人工核验）"
            : "备份目录最近 24 小时无文件，无法确认复杂编辑";
    }

    private String actionableError(String reason) {
        if (reason == null) return "动作被拒；请检查 audit.log";
        if (reason.contains("缺少字段 world") || reason.contains("世界不存在"))
            return "缺少有效世界名；从 /skyisland status 显示的世界名选择，动作必须填写 world";
        if (reason.contains("区块未加载")) return "目标区块未加载；请先由玩家正常加载该区块，再重新提出动作";
        if (reason.contains("目的地不安全")) return "目的地不安全；请选有实体落脚方块、双格净空且在世界边界内的位置";
        if (reason.contains("仅可移除未命名")) return "只可移除未命名的怪物或掉落物；请核对附近实体 UUID";
        return "动作被拒：" + reason + "；请检查 /skyisland status 与动作字段";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String diagnosticError(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
            ? error.getCause() : error;
        if (cause instanceof java.net.ConnectException) return "连接被拒绝；检查专用 Gateway 端口";
        if (cause instanceof java.net.http.HttpTimeoutException) return "请求超时；检查专用 Gateway";
        if (cause instanceof IllegalStateException) return cause.getMessage();
        return cause.getClass().getSimpleName() + "；检查专用实例日志";
    }

    void audit(String entry) {
        recentAudit.addFirst(entry.replace('\n', ' '));
        while (recentAudit.size() > 7) recentAudit.removeLast();
        String line = Instant.now() + " " + entry.replace('\n', ' ') + System.lineSeparator();
        auditIo.execute(() -> {
            try { Files.writeString(getDataFolder().toPath().resolve("audit.log"), line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception e) { getLogger().warning("审计记录写入失败"); }
        });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("claim")) {
                if (auth == null) throw new IllegalArgumentException("正版身份验证已启用，无须认领");
                return auth.command(sender, args);
            }
            if (args.length > 0 && (args[0].equalsIgnoreCase("login") || args[0].equalsIgnoreCase("register")
                || args[0].equalsIgnoreCase("passwd"))) {
                sender.sendMessage(ChatColor.RED + "请在游戏内只输入无参数身份命令；此入口不接受密码");
                return true;
            }
        } catch (RuntimeException failure) { sender.sendMessage(ChatColor.RED + failure.getMessage()); return true; }
        if (sender instanceof Player player && !authenticated(player)) {
            sender.sendMessage(ChatColor.RED + "请先登录天空岛账号");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("laws")) {
            sender.sendMessage(laws.publicSummary());
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("season")) {
            sender.sendMessage(season.summary() + (identityReady() ? "" : "；身份记录不可用，一命规则暂停"));
            return true;
        }
        if (!sender.hasPermission("skyisland.admin")) { sender.sendMessage(ChatColor.RED + "无管理权限"); return true; }
        if (args.length == 0) {
            if (sender instanceof Player p) p.openInventory(menu());
            else sender.sendMessage(metrics());
            return true;
        }
        try {
            switch (args[0].toLowerCase()) {
                case "status" -> sender.sendMessage(metrics() + "OpenClaw=" + gatewayState
                    + (gatewayDetail.isEmpty() ? "" : "，原因=" + gatewayDetail) + ", paused=" + paused
                    + ", guard-bans=" + guard.activeBans() + ", latest-edit=" + actions.latest()
                    + "\n防护身份=" + (getServer().getOnlineMode() ? "正版 UUID" : "天空岛离线账号；可对已登录账号临封，换名可规避")
                    + "；" + backupStatus() + "\n会议=" + (meetingBusy ? "进行中" : lastMeeting)
                    + "\n" + laws.summary() + "\n" + season.summary() + discipline.summary());
                case "doctor" -> {
                    sender.sendMessage("身份验证=" + (getServer().getOnlineMode() ? "正版账号" : auth.available() ? "天空岛离线账号" : "故障；已阻止离线玩家")
                        + "；OpenClaw=" + (client.configured() ? "正在检查五角色" : "未配置凭证")
                        + "；" + backupStatus());
                    if (client.configured()) client.missingAgents().whenComplete((missing, error) -> {
                        if (!isEnabled()) return;
                        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(error == null
                            ? (missing.isEmpty() ? "五位 Agent 已列出；请用 /skyisland ask 分别验证模型回复与记忆" : "缺少 Agent：" + String.join(", ", missing))
                            : "联通检查失败：" + diagnosticError(error)));
                    });
                }
                case "ask" -> {
                    if (args.length < 3) throw new IllegalArgumentException("用法: /skyisland ask <角色英文ID> <问题>");
                    AgentRole role = AgentRole.parse(args[1]);
                    ask(role, "管理员提问：" + String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                        + "\n当前世界摘要：\n" + metrics() + "\n" + laws.summary(), sender);
                    sender.sendMessage("已联系 " + role.display);
                }
                case "meeting" -> {
                    if (args.length > 1) throw new IllegalArgumentException("用法: /skyisland meeting");
                    if (meetingBusy) sender.sendMessage("天空岛会议正在进行");
                    else if (paused || !client.configured()) sender.sendMessage("AI 已暂停或 OpenClaw 未配置，无法召开会议");
                    else { startMeeting(); sender.sendMessage("天空岛会议已开始，结论会通知在线管理员"); }
                }
                case "pause" -> { paused = true; audit("ai-paused by=" + sender.getName()); sender.sendMessage("AI 世界动作已暂停"); }
                case "resume" -> { paused = false; audit("ai-resumed by=" + sender.getName()); sender.sendMessage("AI 世界动作已恢复"); }
                case "confirm" -> {
                    prunePending();
                    if (args.length != 2) throw new IllegalArgumentException("用法: /skyisland confirm <提案ID>");
                    Pending p = pending.get(args[1]);
                    if (p == null || !p.hash.equals("admin")) throw new IllegalArgumentException("没有此管理员待确认动作");
                    if (!actions.recentBackup()) throw new IllegalArgumentException(backupStatus());
                    pending.remove(args[1]);
                    audit("admin-confirmed " + p.action.id() + " by=" + sender.getName());
                    execute(p.action());
                }
                case "undo" -> {
                    if (args.length != 2) throw new IllegalArgumentException("用法: /skyisland undo <编辑ID>");
                    actions.undo(args[1], result -> { audit("undo " + args[1] + " " + result); sender.sendMessage(result); });
                }
                case "evidence" -> {
                    if (args.length != 2) throw new IllegalArgumentException("用法: /skyisland evidence <玩家名或UUID>");
                    sender.sendMessage(guard.evidence(args[1]));
                }
                case "unban" -> {
                    if (args.length != 2) throw new IllegalArgumentException("用法: /skyisland unban <UUID>");
                    guard.unban(args[1], sender.getName());
                    sender.sendMessage("已解除天空岛临时封禁");
                }
                default -> sender.sendMessage("/skyisland [passwd|laws|season|status|doctor|ask|meeting|pause|resume|confirm|undo|evidence|unban]");
            }
        } catch (Exception e) { sender.sendMessage(ChatColor.RED + e.getMessage()); }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender instanceof Player player && !authenticated(player))
            return args.length == 1 ? List.of("login", "register") : List.of();
        if (sender instanceof org.bukkit.command.ConsoleCommandSender && args.length == 1)
            return List.of("claim", "laws", "season", "status", "doctor", "ask", "meeting", "pause", "resume", "confirm", "undo", "evidence", "unban");
        if (!sender.hasPermission("skyisland.admin")) return args.length == 1 ? List.of("passwd", "laws", "season") : List.of();
        if (args.length == 1) return List.of("passwd", "laws", "season", "status", "doctor", "ask", "meeting", "pause", "resume", "confirm", "undo", "evidence", "unban");
        if (args.length == 2 && args[0].equalsIgnoreCase("ask"))
            return Arrays.stream(AgentRole.values()).map(r -> r.id).toList();
        return List.of();
    }

    private Inventory menu() {
        prunePending();
        Inventory inventory = Bukkit.createInventory(null, 54, TITLE);
        inventory.setItem(4, item(Material.NETHER_STAR, "§d天理－法涅斯",
            "§7TPS " + String.format("%.2f", Bukkit.getTPS()[0]), "§7告警 " + alert,
            "§7网关 " + gatewayState, "§7AI 动作 " + (paused ? "已暂停" : "允许")));
        AgentRole[] roles = AgentRole.values();
        Material[] icons = {Material.NETHER_STAR, Material.WITHER_SKELETON_SKULL, Material.FLOWER_POT,
            Material.CLOCK, Material.ENDER_PEARL};
        for (int i = 0; i < roles.length; i++) inventory.setItem(10 + i, item(icons[i], "§e" + roles[i].display,
            "§7" + roles[i].domain, "§7点击请求状态汇报", "§8" + replies.getOrDefault(roles[i], "等待首次回复")));
        int proposalSlot = 19;
        for (Pending p : pending.values()) {
            if (proposalSlot > 26) break;
            inventory.setItem(proposalSlot++, item(Material.WRITABLE_BOOK, "§6提案 " + p.action.id(),
                "§7来源: " + p.from.display, "§7动作: " + p.action.action().get("type").getAsString(),
                "§7状态: " + (p.hash.equals("admin") ? "待管理员确认" : "待法涅斯审批"),
                "§8" + p.action.reason()));
        }
        String metricLine = metrics().replace('\n', ' ');
        inventory.setItem(29, item(Material.COMPARATOR, "§b服务器指标",
            "§7" + (metricLine.length() > 160 ? metricLine.substring(0, 160) + "…" : metricLine),
            "§7完整信息：/skyisland status"));
        inventory.setItem(31, item(Material.PAPER, "§6待确认动作", "§7数量: " + pending.values().stream().filter(p -> p.hash.equals("admin")).count(),
            "§7" + backupStatus(), "§7使用 /skyisland confirm <ID>", "§7最新撤销 ID: " + actions.latest()));
        inventory.setItem(33, item(paused ? Material.LIME_DYE : Material.RED_DYE,
            paused ? "§a恢复 AI 动作" : "§c暂停 AI 动作", "§7点击切换"));
        int auditSlot = 37;
        for (String line : recentAudit) inventory.setItem(auditSlot++, item(Material.BOOK, "§b操作记录",
            "§7" + (line.length() > 100 ? line.substring(0, 100) + "…" : line)));
        inventory.setItem(49, item(Material.SHIELD, "§b稳定性防护", "§7临时封禁: " + guard.activeBans(),
            "§7身份: " + (getServer().getOnlineMode() ? "正版账号" : "离线账号，换名可规避"),
            "§7证据: /skyisland evidence <玩家>"));
        inventory.setItem(48, item(Material.TOTEM_OF_UNDYING, "§6一命赛季", "§7" + season.summary()));
        inventory.setItem(50, item(Material.ENCHANTED_BOOK, "§d天空岛会议",
            "§7" + (meetingBusy ? "进行中" : lastMeeting), "§7手动召开: /skyisland meeting"));
        return inventory;
    }

    private static ItemStack item(Material type, String title, String... lines) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(Arrays.asList(lines));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("skyisland.admin")) return;
        int slot = event.getRawSlot();
        if (slot >= 10 && slot <= 14) {
            AgentRole role = AgentRole.values()[slot - 10];
            ask(role, "管理员从游戏内面板请求你汇报当前领域状态。\n" + metrics(), player);
            player.closeInventory();
        } else if (slot == 33) {
            paused = !paused;
            audit("ai-" + (paused ? "paused" : "resumed") + " by=" + player.getName());
            player.openInventory(menu());
        }
    }
}
