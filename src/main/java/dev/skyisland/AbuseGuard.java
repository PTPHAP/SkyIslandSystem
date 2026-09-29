package dev.skyisland;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;

/** Deterministic rate guard for server stability abuse. This is not a general cheat detector. */
final class AbuseGuard implements Listener, AutoCloseable {
    private static final long BAN_MILLIS = 30L * 60 * 1000;
    private final SkyIslandPlugin plugin;
    private final Path banFile;
    private final Path evidenceFile;
    private final Map<UUID, Long> bans = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> windows = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    AbuseGuard(SkyIslandPlugin plugin) {
        this.plugin = plugin;
        banFile = plugin.getDataFolder().toPath().resolve("guard-bans.properties");
        evidenceFile = plugin.getDataFolder().toPath().resolve("guard-evidence.log");
        if (!Files.exists(banFile)) return;
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(banFile)) {
            data.load(in);
            for (String key : data.stringPropertyNames()) {
                try {
                    long expiry = Long.parseLong(data.getProperty(key));
                    if (expiry > System.currentTimeMillis()) bans.put(UUID.fromString(key), expiry);
                } catch (RuntimeException ignored) { plugin.getLogger().warning("忽略无效的临时封禁记录"); }
            }
        } catch (IOException e) { plugin.getLogger().warning("无法读取临时封禁记录"); }
    }

    int activeBans() {
        bans.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
        return bans.size();
    }

    @EventHandler public void preLogin(AsyncPlayerPreLoginEvent event) {
        Long expiry = bans.get(event.getUniqueId());
        if (expiry == null) return;
        if (expiry <= System.currentTimeMillis()) { bans.remove(event.getUniqueId()); save(); return; }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
            "天空岛稳定性防护：临时封禁至 " + Instant.ofEpochMilli(expiry) + "。联系管理员查看证据。");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("skyisland.admin")) return;
        if (hit(p, "place", 60_000, 600, event.getBlock().getLocation().toVector().toString())) event.setCancelled(true);
        if (event.getBlock().getType() == Material.TNT
            && hit(p, "tnt", 30_000, 32, event.getBlock().getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("skyisland.admin")
            && hit(p, "break", 60_000, 900, event.getBlock().getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("skyisland.admin")) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item != null && item.getType().name().endsWith("_SPAWN_EGG")
            && hit(p, "spawn-egg", 30_000, 64, p.getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("skyisland.admin")
            && hit(p, "command", 30_000, 120, p.getLocation().toVector().toString())) event.setCancelled(true);
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        windows.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private boolean hit(Player player, String signal, long windowMillis, int limit, String location) {
        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + ":" + signal;
        int count = windows.computeIfAbsent(key, ignored -> new WindowCounter()).add(now, windowMillis);
        if (count <= limit) return false;
        if (count == limit + 1) ban(player, signal, count, windowMillis, location);
        return true;
    }

    private void ban(Player player, String signal, int count, long windowMillis, String location) {
        long expiry = System.currentTimeMillis() + BAN_MILLIS;
        bans.put(player.getUniqueId(), expiry);
        String evidence = Instant.now() + " uuid=" + player.getUniqueId() + " name=" + player.getName()
            + " signal=" + signal + " count=" + count + " window-ms=" + windowMillis
            + " world=" + player.getWorld().getName() + " location=" + location
            + " expires=" + Instant.ofEpochMilli(expiry);
        plugin.audit("guard-ban " + evidence);
        plugin.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission("skyisland.admin"))
            .forEach(p -> p.sendMessage(ChatColor.GOLD + "天空岛已临时封禁 " + player.getName()
                + " 30 分钟；证据 /skyisland evidence " + player.getUniqueId()));
        io.execute(() -> {
            try { Files.writeString(evidenceFile, evidence + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
            catch (IOException e) { plugin.getLogger().warning("防护证据写入失败"); }
        });
        save();
        player.kickPlayer(ChatColor.RED + "天空岛稳定性防护：异常高频行为，临时封禁 30 分钟。联系管理员查看证据。");
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
        save();
        plugin.audit("guard-unban uuid=" + uuid + " by=" + by);
    }

    private void save() {
        Map<UUID, Long> snapshot = Map.copyOf(bans);
        io.execute(() -> {
            Properties data = new Properties();
            snapshot.forEach((id, expiry) -> data.setProperty(id.toString(), Long.toString(expiry)));
            Path temp = banFile.resolveSibling(banFile.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { data.store(out, "SkyIslandSystem temporary bans"); }
            catch (IOException e) { plugin.getLogger().warning("临时封禁记录写入失败"); return; }
            try { Files.move(temp, banFile, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { plugin.getLogger().warning("临时封禁记录保存失败"); }
        });
    }

    @Override public void close() { io.shutdown(); }
}
