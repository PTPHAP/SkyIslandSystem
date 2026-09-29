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
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkyIslandPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final String TITLE = ChatColor.DARK_PURPLE + "天空岛体系 · 天理议事厅";
    private final Map<AgentRole, String> replies = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Deque<String> recentAudit = new ArrayDeque<>();
    private final ExecutorService auditIo = Executors.newSingleThreadExecutor();
    private OpenClawClient client;
    private WorldActions actions;
    private AbuseGuard guard;
    private LawBook laws;
    private ShadowDiscipline discipline;
    private boolean paused;
    private String gatewayState = "未验证";
    private String alert = "正常";
    private long lastIncidentReview;

    private record Pending(AgentRole from, WorldActions.Prepared action, String hash, long expiresAt) {}

    private void prunePending() {
        pending.values().removeIf(p -> p.expiresAt() < System.currentTimeMillis());
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        File secret = new File(getDataFolder(), "secrets.yml");
        String token = YamlConfiguration.loadConfiguration(secret).getString("gateway-token", "");
        if (token.isBlank()) token = System.getenv().getOrDefault("SKYISLAND_OPENCLAW_TOKEN", "");
        client = new OpenClawClient(getConfig().getString("gateway-url", "http://127.0.0.1:19789"),
            token, getConfig().getInt("request-timeout-seconds", 25));
        if (!client.configured()) gatewayState = "未配置";
        actions = new WorldActions(this);
        laws = new LawBook(getDataFolder().toPath());
        discipline = new ShadowDiscipline(getDataFolder().toPath());
        guard = new AbuseGuard(this, laws);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(guard, this);
        getCommand("skyisland").setExecutor(this);
        getCommand("skyisland").setTabCompleter(this);
        long period = Math.max(60, getConfig().getLong("review-interval-seconds", 600)) * 20;
        Bukkit.getScheduler().runTaskTimer(this, this::review, 20 * 60, period);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!paused) {
                try { laws.activateDue(message -> Bukkit.broadcastMessage(ChatColor.GOLD + message), this::audit); }
                catch (RuntimeException failure) { getLogger().severe("法令生效失败: " + failure.getMessage()); }
            }
        }, 20 * 20, 20 * 20);
        getLogger().info("天空岛体系已启动；OpenClaw " + (client.configured() ? "已配置" : "等待凭证"));
    }

    @Override public void onDisable() {
        if (actions != null) actions.close();
        if (guard != null) guard.close();
        auditIo.shutdown();
    }

    private void review() {
        prunePending();
        double tps = Bukkit.getTPS()[0];
        long disk = getDataFolder().getUsableSpace();
        alert = tps < 18 || disk < 5_000_000_000L ? "需要检查：TPS 或磁盘空间" : "正常";
        if (paused || !client.configured()) return;
        String metrics = metrics() + "\n" + laws.summary() + "\n" + guard.riskSummary();
        for (AgentRole role : AgentRole.values())
            ask(role, "定期巡查。只根据这些聚合指标诊断你的领域；没有必要动作时 action 为 null。\n" + metrics, null);
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
        return out.toString();
    }

    void reviewIncident(String signal, String evidence) {
        long now = System.currentTimeMillis();
        if (paused || !client.configured() || now - lastIncidentReview < 60_000L) return;
        lastIncidentReview = now;
        AgentRole shadow = switch (signal) {
            case "tnt" -> AgentRole.RONOVA;
            case "spawn-egg" -> AgentRole.NABERIUS;
            case "command" -> AgentRole.ISTAROTH;
            default -> AgentRole.ASMODAY;
        };
        String prompt = "发生了玩家高频行为，保留独立判断；证据不足时不要提出动作。\n" + evidence
            + "\n" + metrics() + "\n" + laws.summary();
        ask(shadow, prompt, null);
        ask(AgentRole.PHANES, "请审视这条真实防护证据及现行法令，自主判断是否需要调整法令；"
            + "不要凭主观猜测处罚玩家。\n" + evidence + "\n" + laws.summary(), null);
    }

    private void ask(AgentRole role, String prompt, CommandSender sender) {
        if (!client.configured()) { if (sender != null) sender.sendMessage("OpenClaw 未配置"); return; }
        client.ask(role, prompt).whenComplete((reply, error) -> {
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                if (error != null) {
                    gatewayState = "请求失败";
                    String failure = "OpenClaw 请求失败: " + error.getClass().getSimpleName();
                    replies.put(role, failure);
                    if (sender != null) sender.sendMessage(ChatColor.RED + failure);
                    return;
                }
                replies.put(role, reply.message());
                gatewayState = "已响应";
                if (sender != null) sender.sendMessage(ChatColor.LIGHT_PURPLE + role.display + ": " + reply.message());
                handleReply(role, reply);
            });
        });
    }

    private void handleReply(AgentRole role, AgentReply reply) {
        prunePending();
        if (role == AgentRole.PHANES && !reply.approvalId().isBlank()) {
            Pending p = pending.remove(reply.approvalId());
            if (p == null || !p.hash.equals(reply.approvalHash())) { audit("approval-rejected invalid-id-or-hash"); return; }
            audit("approval " + p.action.id() + " " + reply.approved()
                + " approver=phanes proposer=" + p.from.id);
            if (reply.approved()) {
                String boundary = discipline.check(p.from, WorldActions.string(p.action.action(), "type"));
                if (boundary.isEmpty()) executeOrQueue(p.action());
                else audit("approval-rejected changed-shadow-scope id=" + p.action.id() + " reason=" + boundary);
            }
        }
        if (reply.action() == null || paused) return;
        try {
            String type = WorldActions.string(reply.action(), "type");
            if (role != AgentRole.PHANES) {
                String boundary = discipline.check(role, type);
                if (!boundary.isEmpty()) {
                    if (boundary.startsWith("权能暂停")) audit("shadow-suspended from=" + role.id + " reason=" + boundary);
                    else audit("shadow-violation " + discipline.violate(role, boundary));
                    return;
                }
            }
            WorldActions.Prepared prepared = prepareAction(reply.action());
            if (role == AgentRole.PHANES) {
                audit("direct-action proposer=phanes id=" + prepared.id());
                executeOrQueue(prepared);
                return;
            }
            String hash = sha256(reply.action().toString());
            pending.put(prepared.id(), new Pending(role, prepared, hash, System.currentTimeMillis() + 3_600_000));
            audit("shadow-proposal " + prepared.id() + " from=" + role.id + " hash=" + hash);
            ask(AgentRole.PHANES, "影子 " + role.display + " 提议以下动作。你只能按世界治理规则审批，不能改写动作。"
                + "\n提案 ID=" + prepared.id() + "\nHASH=" + hash + "\n动作=" + reply.action()
                + "\n只在同意时返回 approval={id,hash,approved:true}；否则 false。", null);
        } catch (RuntimeException invalid) {
            audit("invalid-proposal from=" + role.id + " reason=" + invalid.getMessage());
        }
    }

    private WorldActions.Prepared prepareAction(JsonObject action) {
        String type = WorldActions.string(action, "type");
        switch (type) {
            case "set_law" -> laws.validate(action);
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
        if (paused) return;
        if (!prepared.reason().isEmpty()) {
            pending.put(prepared.id(), new Pending(AgentRole.PHANES, prepared, "admin", System.currentTimeMillis() + 3_600_000));
            audit("admin-confirmation-required " + prepared.id() + " reason=" + prepared.reason());
            Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("skyisland.admin"))
                .forEach(p -> p.sendMessage(ChatColor.GOLD + "天空岛复杂编辑待确认 " + prepared.id()
                    + "：" + prepared.reason() + "。查看 /skyisland，确认 /skyisland confirm " + prepared.id()));
            return;
        }
        execute(prepared);
    }

    private void execute(WorldActions.Prepared prepared) {
        audit("action-start " + prepared.id() + " " + prepared.action());
        java.util.function.Consumer<String> report = result -> {
            audit("action-result " + prepared.id() + " " + result);
            Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("skyisland.admin"))
                .forEach(p -> p.sendMessage(ChatColor.AQUA + "天空岛：" + result));
        };
        try {
            String type = WorldActions.string(prepared.action(), "type");
            switch (type) {
                case "set_law" -> {
                    String result = laws.apply(prepared.action(), guard.hasRecentIncident(), this::audit);
                    if (result.startsWith("法涅斯法令 v")) Bukkit.broadcastMessage(ChatColor.GOLD + result);
                    report.accept(result);
                }
                case "declare_plan" -> {
                    String result = laws.declarePlan(prepared.action(), this::audit);
                    if (result.startsWith("法涅斯公布")) Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + result);
                    report.accept(result);
                }
                case "set_shadow_scope" -> report.accept(discipline.setScope(prepared.action()));
                case "pardon_shadow" -> report.accept(discipline.pardon(WorldActions.string(prepared.action(), "role")));
                default -> actions.execute(prepared, report);
            }
        } catch (RuntimeException invalid) { report.accept("动作拒绝: " + invalid.getMessage()); }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
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
        if (args.length == 1 && args[0].equalsIgnoreCase("laws")) {
            sender.sendMessage(laws.publicSummary());
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
                case "status" -> sender.sendMessage(metrics() + "OpenClaw=" + gatewayState + ", paused=" + paused
                    + ", guard-bans=" + guard.activeBans() + ", latest-edit=" + actions.latest()
                    + "\n" + laws.summary() + discipline.summary());
                case "ask" -> {
                    if (args.length < 3) throw new IllegalArgumentException("用法: /skyisland ask <角色英文ID> <问题>");
                    AgentRole role = AgentRole.parse(args[1]);
                    ask(role, "管理员提问：" + String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                        + "\n当前世界摘要：\n" + metrics() + "\n" + laws.summary(), sender);
                    sender.sendMessage("已联系 " + role.display);
                }
                case "pause" -> { paused = true; audit("ai-paused by=" + sender.getName()); sender.sendMessage("AI 世界动作已暂停"); }
                case "resume" -> { paused = false; audit("ai-resumed by=" + sender.getName()); sender.sendMessage("AI 世界动作已恢复"); }
                case "confirm" -> {
                    prunePending();
                    if (args.length != 2) throw new IllegalArgumentException("用法: /skyisland confirm <提案ID>");
                    Pending p = pending.get(args[1]);
                    if (p == null || !p.hash.equals("admin")) throw new IllegalArgumentException("没有此管理员待确认动作");
                    if (!actions.recentBackup()) throw new IllegalArgumentException("备份目录最近 24 小时无文件，拒绝复杂编辑；请人工核验备份有效性");
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
                default -> sender.sendMessage("/skyisland [laws|status|ask|pause|resume|confirm|undo|evidence|unban]");
            }
        } catch (Exception e) { sender.sendMessage(ChatColor.RED + e.getMessage()); }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("skyisland.admin")) return args.length == 1 ? List.of("laws") : List.of();
        if (args.length == 1) return List.of("laws", "status", "ask", "pause", "resume", "confirm", "undo", "evidence", "unban");
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
        inventory.setItem(29, item(Material.COMPARATOR, "§b服务器指标", "§7" + metrics().replace('\n', ' ')));
        inventory.setItem(31, item(Material.PAPER, "§6待确认动作", "§7数量: " + pending.values().stream().filter(p -> p.hash.equals("admin")).count(),
            "§7使用 /skyisland confirm <ID>", "§7最新撤销 ID: " + actions.latest()));
        inventory.setItem(33, item(paused ? Material.LIME_DYE : Material.RED_DYE,
            paused ? "§a恢复 AI 动作" : "§c暂停 AI 动作", "§7点击切换"));
        int auditSlot = 37;
        for (String line : recentAudit) inventory.setItem(auditSlot++, item(Material.BOOK, "§b操作记录",
            "§7" + (line.length() > 100 ? line.substring(0, 100) + "…" : line)));
        inventory.setItem(49, item(Material.SHIELD, "§b稳定性防护", "§7临时封禁: " + guard.activeBans(),
            "§7证据: /skyisland evidence <玩家>"));
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
