package dev.skyisland;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Stops gameplay until an offline UUID account proves possession of its password. */
final class OfflineAuthGate implements Listener, AutoCloseable {
    private final SkyIslandPlugin plugin;
    private final OfflineIdentity identities;
    private final Path playerData;
    private final ExecutorService hashing = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32));
    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> prompts = new ConcurrentHashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, Boolean> legacy = new HashMap<>();
    private final Map<UUID, Integer> failures = new HashMap<>();
    private final Map<UUID, Long> lockedUntil = new HashMap<>();

    OfflineAuthGate(SkyIslandPlugin plugin) {
        this.plugin = plugin;
        identities = new OfflineIdentity(plugin.getDataFolder().toPath());
        playerData = plugin.getServer().getWorlds().get(0).getWorldFolder().toPath().resolve("playerdata");
    }

    boolean available() { return identities.available(); }
    boolean verified(Player player) { return verified.contains(player.getUniqueId()); }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        String operation = args[0].toLowerCase(java.util.Locale.ROOT);
        if (operation.equals("claim")) {
            if (!(sender instanceof ConsoleCommandSender))
                throw new IllegalArgumentException("认领码只可由服务器控制台生成");
            if (args.length != 2) throw new IllegalArgumentException("用法: skyisland claim <旧玩家名或UUID>");
            UUID id;
            try { id = UUID.fromString(args[1]); }
            catch (IllegalArgumentException notUuid) { id = OfflineIdentity.offlineUuid(args[1]); }
            if (!Files.exists(playerData.resolve(id + ".dat")))
                throw new IllegalArgumentException("没有找到该玩家旧存档；请核对玩家名大小写");
            String code = identities.issueClaim(id);
            plugin.audit("identity-claim-issued uuid=" + id);
            sender.sendMessage("一次性认领码（只私下交给该玩家）：" + code);
            return true;
        }
        if (!operation.equals("register") && !operation.equals("login") && !operation.equals("passwd")) return false;
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("请在游戏内操作");
        if (args.length != 1) throw new IllegalArgumentException("不要在命令中输入密码；只输入 /skyisland " + operation);
        if (operation.equals("passwd") && !verified(player)) throw new IllegalArgumentException("请先登录再修改密码");
        if (!operation.equals("passwd") && verified(player)) { sender.sendMessage("你已经登录"); return true; }
        if (!available()) throw new IllegalStateException("身份记录损坏，离线登录已锁定");
        prompts.put(player.getUniqueId(), operation);
        sender.sendMessage(operation.equals("register")
            ? "§e下一条普通聊天输入：密码 重复密码 [旧玩家认领码]。这条消息不会公开。"
            : operation.equals("passwd")
                ? "§e下一条普通聊天输入：旧密码 新密码 重复新密码。这条消息不会公开。"
                : "§e下一条普通聊天只输入密码。这条消息不会公开。");
        return true;
    }

    private void submit(Player player, String operation, String[] args) {
        UUID id = player.getUniqueId();
        if (!available()) throw new IllegalStateException("身份记录损坏，离线登录已锁定");
        if (lockedUntil.getOrDefault(id, 0L) > System.currentTimeMillis())
            throw new IllegalArgumentException("登录尝试过多，请 10 分钟后重试");
        if (!pending.add(id)) throw new IllegalArgumentException("正在验证，请稍候");
        String name = player.getName();
        boolean oldAccount = legacy.getOrDefault(id, true);
        try {
            if (operation.equals("register") && (args.length != 2 && args.length != 3))
                throw new IllegalArgumentException("请用空格分隔密码、重复密码和可选认领码");
            if (operation.equals("login") && args.length != 1)
                throw new IllegalArgumentException("请只输入密码");
            if (operation.equals("passwd") && args.length != 3)
                throw new IllegalArgumentException("请用空格分隔旧密码、新密码和重复新密码");
            if (operation.equals("register") && !args[0].equals(args[1]))
                throw new IllegalArgumentException("两次密码不一致");
            if (operation.equals("passwd") && !args[1].equals(args[2]))
                throw new IllegalArgumentException("两次新密码不一致");
            String password = args[0];
            String claim = operation.equals("register") && args.length == 3 ? args[2] : "";
            hashing.execute(() -> {
                String result;
                boolean success = false;
                try {
                    if (operation.equals("register")) {
                        identities.register(id, name, password, claim, oldAccount);
                        result = "注册成功，已登录";
                    } else if (operation.equals("passwd")) {
                        identities.changePassword(id, password, args[1]);
                        success = true;
                        result = "密码已更新";
                    } else {
                        success = identities.login(id, password);
                        result = success ? "登录成功" : "密码错误或账号未注册";
                    }
                    if (operation.equals("register")) success = true;
                } catch (RuntimeException error) {
                    result = error.getMessage() == null ? "身份验证失败，请联系服主" : error.getMessage();
                    if (!(error instanceof IllegalArgumentException))
                        plugin.getLogger().warning("身份验证失败: " + error.getClass().getSimpleName());
                }
                boolean passed = success;
                String message = result;
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> finish(player, operation, passed, message));
            });
            player.sendMessage("正在验证身份…");
        } catch (RuntimeException failure) {
            pending.remove(id);
            if (failure instanceof java.util.concurrent.RejectedExecutionException)
                throw new IllegalStateException("身份验证繁忙，请稍后再试");
            throw failure;
        }
    }

    private void finish(Player player, String operation, boolean success, String message) {
        UUID id = player.getUniqueId();
        pending.remove(id);
        if (!player.isOnline()) return;
        player.sendMessage(success ? "§a" + message : "§c" + message);
        plugin.audit("identity-" + (success ? operation : "failed") + " uuid=" + id);
        if (success) {
            failures.remove(id);
            lockedUntil.remove(id);
            if (!operation.equals("passwd")) {
                verified.add(id);
                plugin.onIdentityVerified(player);
            }
        } else {
            int count = failures.merge(id, 1, Integer::sum);
            if (count >= 5) {
                failures.remove(id);
                lockedUntil.put(id, System.currentTimeMillis() + 600_000L);
                player.kickPlayer("登录尝试过多，请 10 分钟后重试");
            }
        }
    }

    @EventHandler public void login(PlayerLoginEvent event) {
        legacy.put(event.getPlayer().getUniqueId(),
            Files.exists(playerData.resolve(event.getPlayer().getUniqueId() + ".dat")));
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!available()) { player.kickPlayer("天空岛身份记录损坏，请联系服务器所有者恢复备份"); return; }
        player.sendMessage(identities.registered(player.getUniqueId())
            ? "§e请使用 /skyisland login，然后在下一条普通聊天输入密码。"
            : legacy.getOrDefault(player.getUniqueId(), true)
                ? "§e旧玩家请向服主索取一次性认领码，再使用 /skyisland register；按提示在普通聊天输入密码、重复密码和认领码。"
                : "§e请使用 /skyisland register，再按提示在普通聊天输入密码和重复密码。请使用本站独有密码。");
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        verified.remove(id);
        prompts.remove(id);
        pending.remove(id);
        legacy.remove(id);
    }

    @EventHandler(priority = EventPriority.LOWEST) public void command(PlayerCommandPreprocessEvent event) {
        String lower = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("/(?:[a-z0-9_]+:)?skyisland\\s+(login|register|passwd)(\\s+.*)?")) {
            String[] parts = event.getMessage().substring(1).split("\\s+");
            event.setCancelled(true);
            event.setMessage("/skyisland " + parts[1] + " [redacted]");
            try { command(event.getPlayer(), java.util.Arrays.copyOfRange(parts, 1, parts.length)); }
            catch (RuntimeException failure) { event.getPlayer().sendMessage("§c" + failure.getMessage()); }
            return;
        }
        if (verified(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("§c请先登录天空岛账号");
    }

    @EventHandler public void move(PlayerMoveEvent event) {
        if (!verified(event.getPlayer()) && event.getTo() != null
            && (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ())) event.setCancelled(true);
    }
    @EventHandler public void teleport(PlayerTeleportEvent event) { if (!verified(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void place(BlockPlaceEvent event) { if (!verified(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void breakBlock(BlockBreakEvent event) { if (!verified(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void interact(PlayerInteractEvent event) { if (!verified(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void interactEntity(PlayerInteractEntityEvent event) { if (!verified(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent event) { if (!verified(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !verified(player)) event.setCancelled(true);
    }
    @EventHandler public void consume(PlayerItemConsumeEvent event) { if (!verified(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void inventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !verified(player)) event.setCancelled(true);
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && !verified(player)) event.setCancelled(true);
    }
    @EventHandler public void creative(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player player && !verified(player)) event.setCancelled(true);
    }
    @EventHandler public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && !verified(player)) event.setCancelled(true);
    }
    @EventHandler public void attack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && !verified(player)) event.setCancelled(true);
    }
    @EventHandler public void chat(AsyncPlayerChatEvent event) {
        if (verified(event.getPlayer()) && !prompts.containsKey(event.getPlayer().getUniqueId())) return;
        String secret = event.getMessage();
        event.setCancelled(true);
        event.setMessage("[redacted]");
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            String operation = prompts.remove(player.getUniqueId());
            if (!player.isOnline() || operation == null) return;
            try { submit(player, operation, secret.trim().split("\\s+")); }
            catch (RuntimeException failure) { player.sendMessage("§c" + failure.getMessage()); }
        });
    }

    @Override public void close() { hashing.shutdown(); }
}
