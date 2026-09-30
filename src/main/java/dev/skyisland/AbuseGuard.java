package dev.skyisland;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;

/** Deterministic rate guard for server stability abuse. This is not a general cheat detector. */
final class AbuseGuard implements Listener, AutoCloseable {
    private final SkyIslandPlugin plugin;
    private final LawBook laws;
    private final Path banFile;
    private final Path evidenceFile;
    private final Map<UUID, Long> bans = new ConcurrentHashMap<>();
    private final Map<String, Long> strikes = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> windows = new ConcurrentHashMap<>();
    private final Map<UUID, Long> kickCooldown = new HashMap<>();
    private final Map<String, Integer> incidentCounts = new HashMap<>();
    private final Map<String, Deque<Long>> recentIncidents = new HashMap<>();
    private final Map<String, Long> lastIncidentBySignal = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    AbuseGuard(SkyIslandPlugin plugin, LawBook laws) {
        this.plugin = plugin;
        this.laws = laws;
        banFile = plugin.getDataFolder().toPath().resolve("guard-bans.properties");
        evidenceFile = plugin.getDataFolder().toPath().resolve("guard-evidence.log");
        if (!Files.exists(banFile)) return;
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(banFile)) {
            data.load(in);
            for (String key : data.stringPropertyNames()) {
                try {
                    long value = Long.parseLong(data.getProperty(key));
                    if (key.startsWith("strike.")) {
                        String incident = key.substring(7);
                        UUID.fromString(incident.substring(0, incident.indexOf(':')));
                        if (value > System.currentTimeMillis() - 86_400_000L) strikes.put(incident, value);
                    } else if (value > System.currentTimeMillis()) bans.put(UUID.fromString(key), value);
                } catch (RuntimeException ignored) { plugin.getLogger().warning("忽略无效的临时封禁记录"); }
            }
        } catch (IOException e) { plugin.getLogger().warning("无法读取临时封禁记录"); }
    }

    int activeBans() {
        bans.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
        return bans.size();
    }

    @EventHandler public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.getServer().getOnlineMode()) return;
        Long expiry = bans.get(event.getUniqueId());
        if (expiry == null) return;
        if (expiry <= System.currentTimeMillis()) { bans.remove(event.getUniqueId()); save(); return; }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
            "天空岛稳定性防护：临时封禁至 " + Instant.ofEpochMilli(expiry) + "。联系管理员查看证据。");
    }

    boolean enforceExistingBan(Player player) {
        if (player.hasPermission("skyisland.admin")) return false;
        Long expiry = bans.get(player.getUniqueId());
        if (expiry == null) return false;
        if (expiry <= System.currentTimeMillis()) { bans.remove(player.getUniqueId()); save(); return false; }
        player.kickPlayer("天空岛稳定性防护：临时封禁至 " + Instant.ofEpochMilli(expiry));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("skyisland.admin")) return;
        if (hit(p, "place", event.getBlock().getLocation().toVector().toString())) event.setCancelled(true);
        if (event.getBlock().getType() == Material.TNT
            && hit(p, "tnt", event.getBlock().getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("skyisland.admin")
            && hit(p, "break", event.getBlock().getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("skyisland.admin")) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item != null && item.getType().name().endsWith("_SPAWN_EGG")
            && hit(p, "spawn-egg", p.getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("skyisland.admin")
            && hit(p, "command", p.getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        windows.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private boolean hit(Player player, String signal, String location) {
        LawBook.Rule rule = laws.rule(signal);
        long windowMillis = rule.windowSeconds() * 1000L;
        int limit = rule.limit();
        long now = System.currentTimeMillis();
        if (kickCooldown.getOrDefault(player.getUniqueId(), 0L) > now) return true;
        kickCooldown.remove(player.getUniqueId());
        String key = player.getUniqueId() + ":" + signal;
        int count = windows.computeIfAbsent(key, ignored -> new WindowCounter()).add(now, windowMillis);
        if (count == Math.max(1, limit * 3 / 4)) {
            String authority = switch (signal) {
                case "tnt" -> "若娜瓦";
                case "spawn-egg" -> "纳贝里士";
                case "command" -> "伊斯塔露";
                default -> "阿斯莫代";
            };
            player.sendMessage(ChatColor.GOLD + authority + "预警：你的 " + signal + " 操作接近当前防护阈值，请减缓频率。");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.0f);
        }
        if (count <= limit) return false;
        if (count == limit + 1) enforce(player, signal, count, windowMillis, location, rule);
        return true;
    }

    private void enforce(Player player, String signal, int count, long windowMillis, String location, LawBook.Rule rule) {
        boolean verifiedIdentity = plugin.authenticated(player);
        long now = System.currentTimeMillis();
        String strikeKey = player.getUniqueId() + ":" + signal;
        long previous = strikes.getOrDefault(strikeKey, 0L);
        boolean ban = verifiedIdentity && PenaltyPolicy.temporaryBan(signal, previous, now);
        long expiry = now + rule.banMinutes() * 60_000L;
        lastIncidentBySignal.put(signal, now);
        incidentCounts.merge(signal, 1, Integer::sum);
        Deque<Long> recent = recentIncidents.computeIfAbsent(signal, ignored -> new ArrayDeque<>());
        recent.addLast(now);
        while (!recent.isEmpty() && now - recent.peekFirst() > 5 * 60_000L) recent.removeFirst();
        if (verifiedIdentity) strikes.put(strikeKey, now);
        if (ban) bans.put(player.getUniqueId(), expiry);
        UUID uuid = player.getUniqueId();
        long cooldownEnds = now + 30_000L;
        kickCooldown.put(uuid, cooldownEnds);
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> kickCooldown.remove(uuid, cooldownEnds), 20L * 30);
        String evidence = Instant.now() + " uuid=" + player.getUniqueId() + " name=" + player.getName()
            + " signal=" + signal + " law-version=" + rule.version() + " count=" + count + " window-ms=" + windowMillis
            + " world=" + player.getWorld().getName() + " location=" + location
            + " penalty=" + (ban ? "temporary-ban" : "kick")
            + " previous-incident=" + (previous == 0 ? "none" : Instant.ofEpochMilli(previous))
            + " expires=" + (ban ? Instant.ofEpochMilli(expiry) : "none");
        plugin.audit((ban ? "guard-ban " : "guard-kick ") + evidence);
        String caseId = plugin.reviewIncident(signal, evidence);
        plugin.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission("skyisland.admin"))
            .forEach(p -> p.sendMessage(ChatColor.GOLD + "天空岛已" + (ban ? "临时封禁" : "踢出")
                + player.getName() + "；证据 /skyisland evidence " + player.getUniqueId()));
        io.execute(() -> {
            try { Files.writeString(evidenceFile, evidence + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
            catch (IOException e) { plugin.getLogger().warning("防护证据写入失败"); }
        });
        if (verifiedIdentity) save();
        String contact = plugin.getConfig().getString("appeal-contact", "");
        player.kickPlayer(ChatColor.RED + "天空岛稳定性防护：" + signal + " 高频行为已拦截；案件=" + caseId
            + "；" + (ban ? "临封截止=" + Instant.ofEpochMilli(expiry) : "本次踢出")
            + "；申诉=" + (contact.isBlank() ? "外部申诉入口未配置" : contact));
    }

    boolean hasRecentIncident(String signal) {
        return System.currentTimeMillis() - lastIncidentBySignal.getOrDefault(signal, 0L) < 5 * 60_000L;
    }

    boolean severeIncident(String signal) {
        Deque<Long> recent = recentIncidents.get(signal);
        if (recent == null) return false;
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst() > 5 * 60_000L) recent.removeFirst();
        return !recent.isEmpty() && (signal.equals("tnt") || signal.equals("spawn-egg") || recent.size() >= 3);
    }

    int incidentCount(String signal) { return incidentCounts.getOrDefault(signal, 0); }

    String riskSummary() {
        StringBuilder out = new StringBuilder("接近防护阈值的玩家：");
        int shown = 0;
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (String signal : new String[]{"place", "break", "tnt", "spawn-egg", "command"}) {
                WindowCounter counter = windows.get(player.getUniqueId() + ":" + signal);
                if (counter == null) continue;
                LawBook.Rule rule = laws.rule(signal);
                int count = counter.count(now, rule.windowSeconds() * 1000L);
                if (count < rule.limit() / 2) continue;
                out.append(" ").append(player.getName()).append("/").append(signal)
                    .append("=").append(count).append("/").append(rule.limit());
                if (++shown == 5) return out.toString();
            }
        }
        return shown == 0 ? out.append("无").toString() : out.toString();
    }

    String evidence(String identity) {
        if (!Files.exists(evidenceFile)) return "暂无防护证据";
        try (var lines = Files.lines(evidenceFile)) {
            return lines.filter(line -> line.contains("uuid=" + identity) || line.contains("name=" + identity + " "))
                .reduce((previous, current) -> current).orElse("未找到该玩家的防护证据");
        } catch (IOException e) { return "证据文件无法读取"; }
    }

    void unban(String id, String by) {
        UUID uuid = UUID.fromString(id);
        bans.remove(uuid);
        strikes.keySet().removeIf(key -> key.startsWith(uuid + ":"));
        save();
        plugin.audit("guard-unban uuid=" + uuid + " by=" + by);
    }

    private void save() {
        Map<UUID, Long> snapshot = Map.copyOf(bans);
        Map<String, Long> incidentSnapshot = Map.copyOf(strikes);
        io.execute(() -> {
            Properties data = new Properties();
            snapshot.forEach((id, expiry) -> data.setProperty(id.toString(), Long.toString(expiry)));
            incidentSnapshot.forEach((key, time) -> data.setProperty("strike." + key, Long.toString(time)));
            Path temp = banFile.resolveSibling(banFile.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { data.store(out, "SkyIslandSystem temporary bans"); }
            catch (IOException e) { plugin.getLogger().warning("临时封禁记录写入失败"); return; }
            try { Files.move(temp, banFile, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { plugin.getLogger().warning("临时封禁记录保存失败"); }
        });
    }

    @Override public void close() { io.shutdown(); }
}
