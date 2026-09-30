package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Account sanctions and escrow stay in plugin-private records, never the server's ban/operator files. */
final class PlayerGovernance implements Listener {
    private static final Set<String> LAWS = Set.of("place", "break", "tnt", "spawn-egg", "command");
    private static final Set<Material> CONTROL_ITEMS = Set.of(Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK, Material.COMMAND_BLOCK_MINECART, Material.STRUCTURE_BLOCK, Material.JIGSAW);
    private final SkyIslandPlugin plugin;
    private final GovernanceLedger ledger;
    private final Map<UUID, Map<String, Integer>> counts = new HashMap<>();
    private final Map<UUID, Long> since = new HashMap<>();
    private final Map<String, Integer> worldCounts = new HashMap<>();
    private long worldSince;
    private volatile Map<UUID, String> banMessages = Map.of();
    PlayerGovernance(SkyIslandPlugin plugin, GovernanceLedger ledger) { this.plugin = plugin; this.ledger = ledger; refreshBans(); }
    void recordGuard(String caseId,String signal,UUID subject,String evidence) {
        if(subject==null)return;
        java.util.regex.Matcher expiry=java.util.regex.Pattern.compile("expires=([^ ]+)").matcher(evidence);
        long until=expiry.find() && !expiry.group(1).equals("none")?java.time.Instant.parse(expiry.group(1)).toEpochMilli():0;
        JsonObject s=new JsonObject();s.addProperty("source","guard");s.addProperty("case_id",caseId);s.addProperty("law",signal);
        s.addProperty("subject",subject.toString());s.addProperty("kind",until>0?"tempban":"kick");s.addProperty("until",until);
        s.addProperty("active",until>System.currentTimeMillis());s.addProperty("reason","本地高频保护："+signal+"；原始测量见案件");
        ledger.section("sanctions").add(caseId,s);ledger.save();refreshBans();
        ledger.record(caseId,"execution","system","本地防护实际执行；处罚编号="+caseId+"；kind="+s.get("kind")+"；until="+until);
    }
    void reconcileGuard() {
        for(var entry:ledger.section("sanctions").entrySet()) {
            JsonObject s=entry.getValue().getAsJsonObject();
            if("guard".equals(AgentReply.string(s,"source", "")) && !s.get("active").getAsBoolean() && s.get("until").getAsLong()>System.currentTimeMillis())
                plugin.revokeGuardBan(UUID.fromString(s.get("subject").getAsString()));
        }
    }

    void verified(Player p) {
        String id = p.getUniqueId().toString();
        JsonObject profile = ledger.section("profiles").has(id) ? ledger.section("profiles").getAsJsonObject(id) : new JsonObject();
        boolean fresh = !profile.has("guided");
        profile.addProperty("name", p.getName()); profile.addProperty("verified", true);
        profile.addProperty("admin", p.hasPermission("skyisland.admin"));
        if (fresh) profile.addProperty("guided", false);
        ledger.section("profiles").add(id, profile); ledger.save(); refreshBans();
        recoverInventory(p);
        if (fresh) p.sendMessage("§d降临者，欢迎来到天空岛。§e /skyisland guide 阅读引导；完成后获得神之眼身份（服务器同人设定）。");
        showIdentity(p);
    }
    private void showIdentity(Player p) {
        JsonObject profile = ledger.section("profiles").getAsJsonObject(p.getUniqueId().toString());
        if (profile != null && profile.get("guided").getAsBoolean()) {
            String marker = "§b[神之眼] §r";
            if (!p.getDisplayName().startsWith(marker)) p.setDisplayName(marker + p.getDisplayName());
        }
    }
    void guide(Player p) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK); BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("天空岛 · 降临者引导"); meta.setAuthor("天空岛体系");
        meta.setPages("欢迎，降临者。\n本服以原神天空岛意象创作，并非官方剧情。\n探索、建造与合作是日常玩法。\n五执政根据真实证据维护世界。",
            "公开法度：/skyisland laws\n个人身份：/skyisland profile\n案件：/skyisland cases\n查看：/skyisland case <ID>\n申诉：/skyisland appeal <ID> <理由>\n完成引导：/skyisland guide complete",
            plugin.publicLaws(), "处罚会提供案件编号、理由和期限。\n管理员豁免处罚。\n被封禁后外部申诉：" + contact());
        book.setItemMeta(meta); p.openBook(book);
        p.sendMessage("§e已打开引导书。阅读后 /skyisland guide complete 领取身份称号。");
    }
    void completeGuide(Player p) {
        JsonObject profile = ledger.section("profiles").getAsJsonObject(p.getUniqueId().toString());
        if (profile == null) throw new IllegalArgumentException("身份未验证");
        profile.addProperty("guided", true); ledger.save(); showIdentity(p);
        p.sendMessage("§b已获神之眼身份称号。此为本服身份，不授予管理权限。");
    }
    String profile(Player p) {
        return "降临者 " + p.getName() + "；" + ledger.section("profiles").get(p.getUniqueId().toString())
            + "；处罚=" + activeFor(p.getUniqueId()) + "；短期活动=" + observation(p.getUniqueId());
    }
    String observation(UUID id) {
        if (System.currentTimeMillis() - since.getOrDefault(id, 0L) > 600_000) { counts.remove(id); since.remove(id); }
        return "最近十分钟计数=" + counts.getOrDefault(id, Map.of()) + "；世界活动=" + worldCounts;
    }
    private void observed(Player p, String kind) {
        if (!plugin.authenticated(p)) return;
        long now = System.currentTimeMillis();
        if (now - since.getOrDefault(p.getUniqueId(), 0L) > 600_000) { counts.remove(p.getUniqueId()); since.put(p.getUniqueId(), now); }
        counts.computeIfAbsent(p.getUniqueId(), ignored -> new HashMap<>()).merge(kind, 1, Integer::sum);
    }
    private void worldEvent(String kind) {
        if (System.currentTimeMillis() - worldSince > 600_000) { worldCounts.clear(); worldSince = System.currentTimeMillis(); }
        worldCounts.merge(kind, 1, Integer::sum);
    }
    void tick() {
        boolean changed = false;
        for (var e : ledger.section("sanctions").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            if (s.get("active").getAsBoolean() && s.get("until").getAsLong() > 0 && s.get("until").getAsLong() <= System.currentTimeMillis()) {
                s.addProperty("active", false); changed = true;
                ledger.record(s.get("case_id").getAsString(), "execution", "system", "处罚 " + e.getKey() + " 已到期");
                Player p = Bukkit.getPlayer(UUID.fromString(s.get("subject").getAsString()));
                if (p != null) p.sendMessage("§a天空岛处罚 " + e.getKey() + " 已到期，相关能力已恢复。");
            }
        }
        if (changed) { ledger.save(); refreshBans(); }
        since.keySet().removeIf(id -> { if (System.currentTimeMillis() - since.get(id) < 600_000) return false; counts.remove(id); return true; });
    }
    String contact() { String value = plugin.getConfig().getString("appeal-contact", ""); return value.isBlank() ? "外部申诉入口未配置" : value; }
    int unban(UUID subject, String actor) {
        int count=0;
        for(var entry:ledger.section("sanctions").entrySet()) {
            JsonObject s=entry.getValue().getAsJsonObject();
            if(!s.get("subject").getAsString().equals(subject.toString()) || !s.get("active").getAsBoolean()
                || !Set.of("tempban","permanentban").contains(s.get("kind").getAsString()))continue;
            s.addProperty("active",false);count++;ledger.record(s.get("case_id").getAsString(),"correction",actor,"管理恢复账号登录；原处罚="+entry.getKey());
        }
        if(count>0){ledger.save();refreshBans();}return count;
    }
    private List<JsonObject> activeFor(UUID id) {
        return ledger.section("sanctions").entrySet().stream().map(e -> e.getValue().getAsJsonObject())
            .filter(s -> s.get("subject").getAsString().equals(id.toString()) && s.get("active").getAsBoolean()
                && (s.get("until").getAsLong() == 0 || s.get("until").getAsLong() > System.currentTimeMillis())).toList();
    }
    private void refreshBans() {
        Map<UUID, String> result = new HashMap<>();
        for (var e : ledger.section("sanctions").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject(); String kind = s.get("kind").getAsString();
            if (!Set.of("tempban", "permanentban").contains(kind) || !s.get("active").getAsBoolean()) continue;
            long until = s.get("until").getAsLong(); if (until != 0 && until <= System.currentTimeMillis()) continue;
            UUID id = UUID.fromString(s.get("subject").getAsString());
            JsonObject profile = ledger.section("profiles").getAsJsonObject(id.toString());
            if (profile != null && profile.get("admin").getAsBoolean()) continue;
            result.put(id, "天空岛裁决：" + s.get("reason").getAsString() + "；案件=" + s.get("case_id").getAsString()
                + "；" + (until == 0 ? "永久封禁（可复核）" : "截止=" + java.time.Instant.ofEpochMilli(until)) + "；申诉=" + contact());
        }
        banMessages = Map.copyOf(result);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void login(PlayerLoginEvent e) {
        String message = banMessages.get(e.getPlayer().getUniqueId());
        if (message != null && !e.getPlayer().hasPermission("skyisland.admin")) e.disallow(PlayerLoginEvent.Result.KICK_BANNED, message);
    }
    boolean enforceBan(Player p) {
        if (p.hasPermission("skyisland.admin")) return false;
        String message = banMessages.get(p.getUniqueId()); if (message == null) return false;
        p.kickPlayer(message); return true;
    }
    private boolean blocked(Player p, String capability) {
        if (p.hasPermission("skyisland.admin")) return false;
        return activeFor(p.getUniqueId()).stream().anyMatch(s -> s.get("kind").getAsString().equals("restrict")
            && s.get("capability").getAsString().equals(capability));
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void place(BlockPlaceEvent e) { observed(e.getPlayer(), "place"); if (blocked(e.getPlayer(), "place")) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void broken(BlockBreakEvent e) { observed(e.getPlayer(), "break"); if (blocked(e.getPlayer(), "break")) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void interact(PlayerInteractEvent e) { if (blocked(e.getPlayer(), "interact")) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void interactEntity(PlayerInteractEntityEvent e) { if (blocked(e.getPlayer(), "interact")) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void inventoryClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && blocked(p,"interact")) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void inventoryDrag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && blocked(p,"interact")) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e) {
        if (e.getMessage().toLowerCase(java.util.Locale.ROOT).startsWith("/skyisland")) return;
        observed(e.getPlayer(), "command"); if (blocked(e.getPlayer(), "command")) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void combat(EntityDamageByEntityEvent e) {
        Player p = e.getDamager() instanceof Player direct ? direct : e.getDamager() instanceof org.bukkit.entity.Projectile shot && shot.getShooter() instanceof Player shooter ? shooter : null;
        if (p != null) { observed(p, "combat"); if (blocked(p, "combat")) e.setCancelled(true); }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(EntityDeathEvent e) { worldEvent("death"); if (e.getEntity() instanceof Player p) observed(p, "death"); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void teleport(PlayerTeleportEvent e) { observed(e.getPlayer(), "teleport"); }
    @EventHandler(priority=EventPriority.MONITOR) public void dimension(PlayerChangedWorldEvent e) { observed(e.getPlayer(), "dimension"); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void spawn(CreatureSpawnEvent e) { worldEvent("spawn"); }
    @EventHandler(priority=EventPriority.MONITOR) public void chunk(ChunkLoadEvent e) { worldEvent("chunk-load"); }

    private UUID subject(JsonObject action) {
        JsonObject c = ledger.requireCase(WorldActions.string(action, "case_id"));
        if (!LAWS.contains(c.get("signal").getAsString())) throw new IllegalArgumentException("此案件没有已实施法度，不能作为处罚依据");
        if (!WorldActions.string(action, "law").equals(c.get("signal").getAsString())) throw new IllegalArgumentException("law 必须引用案件实际触发的法度信号");
        UUID id = UUID.fromString(c.get("subject").getAsString());
        JsonObject profile = ledger.section("profiles").getAsJsonObject(id.toString());
        Player p = Bukkit.getPlayer(id);
        if (profile == null || !profile.get("verified").getAsBoolean()) throw new IllegalArgumentException("玩家身份尚未验证");
        boolean administrator=p!=null?p.hasPermission("skyisland.admin"):profile.get("admin").getAsBoolean() || Bukkit.getOfflinePlayer(id).isOp();
        if(p!=null && profile.get("admin").getAsBoolean()!=administrator){profile.addProperty("admin",administrator);ledger.save();refreshBans();}
        if (administrator)
            throw new IllegalArgumentException("管理员账号豁免处罚");
        return id;
    }
    void validate(JsonObject a) {
        String type = WorldActions.string(a, "type");
        if (type.equals("punish_player")) {
            subject(a); String kind = WorldActions.string(a, "kind");
            if (!Set.of("warn", "restrict", "kick", "tempban", "permanentban").contains(kind)) throw new IllegalArgumentException("处罚类型无效");
            String reason = WorldActions.string(a, "reason"); if (reason.isBlank() || reason.length() > 240) throw new IllegalArgumentException("处罚原因须 1..240 字");
            if (Set.of("restrict", "tempban").contains(kind)) WorldActions.number(a, "minutes", 1, 43_200);
            if (kind.equals("restrict") && !Set.of("place", "break", "command", "interact", "combat").contains(WorldActions.string(a, "capability"))) throw new IllegalArgumentException("限制能力无效");
        } else if (type.equals("pardon_player")) {
            if (!ledger.section("sanctions").has(WorldActions.string(a, "sanction_id"))) throw new IllegalArgumentException("处罚编号不存在");
        } else if (type.equals("restore_items")) {
            if (!ledger.section("escrow").has(WorldActions.string(a, "escrow_id"))) throw new IllegalArgumentException("保管编号不存在");
        } else {
            Player p = player(a);
            if (Set.of("confiscate_item", "set_player_mode", "set_effect").contains(type) && p.hasPermission("skyisland.admin")) throw new IllegalArgumentException("管理员不受没收或强制限制");
            if (type.equals("confiscate_item")) { if (!subject(a).equals(p.getUniqueId())) throw new IllegalArgumentException("玩家与案件身份不一致"); }
            if (type.equals("give_item") || type.equals("confiscate_item")) {
                Material m = Material.matchMaterial(WorldActions.string(a, "material"));
                if (m == null || !m.isItem() || m.isAir() || CONTROL_ITEMS.contains(m)) throw new IllegalArgumentException("物品类型不可用");
                WorldActions.number(a, "count", 1, 2304);
            }
            if (type.equals("set_effect")) {
                if (effect(a) == null) throw new IllegalArgumentException("效果不存在");
                WorldActions.number(a, "seconds", 1, 3600); WorldActions.number(a, "amplifier", 0, 4);
            }
            if (type.equals("set_player_mode")) {
                GameMode mode = GameMode.valueOf(WorldActions.string(a, "mode"));
                if (plugin.eliminated(p) && mode != GameMode.SPECTATOR) throw new IllegalArgumentException("一命赛季淘汰状态不能被游戏模式绕过");
            }
        }
    }
    private Player player(JsonObject a) {
        Player p = Bukkit.getPlayerExact(WorldActions.string(a, "player"));
        if (p == null || !plugin.authenticated(p)) throw new IllegalArgumentException("目标玩家不在线或身份未验证");
        return p;
    }
    private PotionEffectType effect(JsonObject a) {
        String key = WorldActions.string(a, "effect").toLowerCase(java.util.Locale.ROOT);
        PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(key));
        return type == null ? PotionEffectType.getByName(key.toUpperCase(java.util.Locale.ROOT)) : type;
    }
    String execute(String operation, JsonObject a) {
        validate(a); String type = WorldActions.string(a, "type");
        if (type.equals("punish_player")) {
            UUID id = subject(a); String kind = WorldActions.string(a, "kind");
            JsonObject s = a.deepCopy(); s.addProperty("subject", id.toString());
            s.addProperty("until", Set.of("restrict", "tempban").contains(kind) ? System.currentTimeMillis() + a.get("minutes").getAsLong() * 60_000 : 0);
            s.addProperty("active", Set.of("restrict", "tempban", "permanentban").contains(kind));
            String notification = "天理裁决：" + s.get("reason").getAsString() + "；案件=" + s.get("case_id").getAsString()
                + "；处罚=" + operation + "/" + kind + "；期限=" + (s.get("until").getAsLong() == 0 ? kind.equals("permanentban") ? "永久（可复核）" : "本次" : java.time.Instant.ofEpochMilli(s.get("until").getAsLong()))
                + "；申诉=" + contact();
            ledger.section("sanctions").add(operation, s);
            JsonObject operationRecord=ledger.section("operations").getAsJsonObject(operation);
            operationRecord.addProperty("state","DONE");operationRecord.addProperty("result",notification);
            ledger.save(); refreshBans();
            Player p = Bukkit.getPlayer(id);
            if (p != null) { if (Set.of("kick", "tempban", "permanentban").contains(kind)) p.kickPlayer(notification); else p.sendMessage("§6" + notification); }
            return notification;
        }
        if (type.equals("pardon_player")) {
            JsonObject s = ledger.section("sanctions").getAsJsonObject(a.get("sanction_id").getAsString());
            s.addProperty("active", false);
            ledger.section("operations").getAsJsonObject(operation).addProperty("state","DONE");
            ledger.save(); refreshBans();
            if("guard".equals(AgentReply.string(s,"source", "")))plugin.revokeGuardBan(UUID.fromString(s.get("subject").getAsString()));
            ledger.record(s.get("case_id").getAsString(), "correction", "phanes", "处罚撤销=" + a.get("sanction_id"));
            return "处罚已撤销=" + a.get("sanction_id");
        }
        if (type.equals("restore_items")) return restore(operation, a.get("escrow_id").getAsString());
        Player p = player(a);
        if (type.equals("set_effect")) { if(!p.addPotionEffect(new PotionEffect(effect(a), a.get("seconds").getAsInt() * 20, a.get("amplifier").getAsInt())))throw new IllegalArgumentException("效果未应用，可能被现有效果或服务器事件阻止"); return "效果已应用=" + p.getName(); }
        if (type.equals("set_player_mode")) { GameMode mode=GameMode.valueOf(a.get("mode").getAsString());p.setGameMode(mode);if(p.getGameMode()!=mode)throw new IllegalArgumentException("模式切换被服务器事件取消"); return "模式已设置=" + p.getName(); }
        ItemStack[] before = p.getInventory().getContents(), after = cloneItems(before);
        Material material = Material.matchMaterial(a.get("material").getAsString()); int wanted = a.get("count").getAsInt();
        List<ItemStack> seized = new ArrayList<>();
        if (type.equals("confiscate_item")) {
            int left = wanted;
            for (int i = 0; i < after.length && left > 0; i++) {
                ItemStack item = after[i]; if (item == null || item.getType() != material) continue;
                int n = Math.min(left, item.getAmount()); ItemStack taken = item.clone(); taken.setAmount(n); seized.add(taken);
                left -= n; if (n == item.getAmount()) after[i] = null; else item.setAmount(item.getAmount() - n);
            }
            if (left == wanted) throw new IllegalArgumentException("玩家没有该物品，未执行没收");
            JsonObject escrow = a.deepCopy(); escrow.addProperty("subject", p.getUniqueId().toString()); escrow.add("items", encode(seized.toArray(ItemStack[]::new))); escrow.addProperty("state", "SEIZING");
            ledger.section("escrow").add(operation, escrow); ledger.save();
        } else add(after, new ItemStack(material), wanted);
        inventoryChange(operation, p, before, after, type.equals("confiscate_item") ? operation : "", "HELD");
        return type.equals("give_item") ? "物品已给予 " + p.getName() + " 数量=" + wanted : "物品已保管；保管编号=" + operation + " 数量=" + seized.stream().mapToInt(ItemStack::getAmount).sum();
    }
    private String restore(String operation, String id) {
        JsonObject escrow = ledger.section("escrow").getAsJsonObject(id);
        if (!"HELD".equals(escrow.get("state").getAsString())) throw new IllegalArgumentException("物品非待归还状态，请先核查记录");
        Player p = Bukkit.getPlayer(UUID.fromString(escrow.get("subject").getAsString()));
        if (p == null || !plugin.authenticated(p)) throw new IllegalArgumentException("玩家不在线或未验证，保留物品待归还");
        ItemStack[] before = p.getInventory().getContents(), after = cloneItems(before);
        for (ItemStack item : decode(escrow.getAsJsonArray("items"))) if (item != null) add(after, item, item.getAmount());
        inventoryChange(operation, p, before, after, id, "RETURNED");
        p.sendMessage("§a天理已归还保管物品，编号=" + id); return "物品已归还=" + id;
    }
    private void inventoryChange(String id, Player p, ItemStack[] before, ItemStack[] after, String escrow, String finalState) {
        JsonObject c = new JsonObject(); c.addProperty("subject", p.getUniqueId().toString()); c.add("before", encode(before)); c.add("after", encode(after));
        c.addProperty("state", "APPLYING"); c.addProperty("escrow", escrow); c.addProperty("finalState", finalState); ledger.checkpoint(id, c);
        if (!encode(p.getInventory().getContents()).equals(c.get("before"))) throw new IllegalArgumentException("玩家物品发生并发变化，停止操作");
        p.getInventory().setContents(after); p.saveData();
        c.addProperty("state", "APPLIED"); ledger.checkpoint(id, c);
        if (!escrow.isBlank()) { ledger.section("escrow").getAsJsonObject(escrow).addProperty("state", finalState); ledger.save(); }
    }
    private void recoverInventory(Player p) {
        for (var entry : ledger.section("operations").entrySet()) {
            JsonObject c = ledger.checkpoint(entry.getKey());
            if (!c.has("subject") || !c.get("subject").getAsString().equals(p.getUniqueId().toString())) continue;
            if (c.get("state").getAsString().equals("APPLIED")) {
                String escrow=c.get("escrow").getAsString();
                if(!escrow.isBlank() && !ledger.section("escrow").getAsJsonObject(escrow).get("state").getAsString().equals(c.get("finalState").getAsString())) {
                    ledger.section("escrow").getAsJsonObject(escrow).addProperty("state",c.get("finalState").getAsString());ledger.save();
                }
                JsonObject op=entry.getValue().getAsJsonObject();
                if(!"DONE".equals(op.get("state").getAsString())) { op.addProperty("state","DONE");op.addProperty("result","重启核对物品操作已完成");ledger.save(); }
                continue;
            }
            if (!c.get("state").getAsString().equals("APPLYING")) continue;
            JsonArray actual = encode(p.getInventory().getContents());
            if (actual.equals(c.get("before"))) { p.getInventory().setContents(decode(c.getAsJsonArray("after"))); p.saveData(); }
            else if (!actual.equals(c.get("after"))) { c.addProperty("state", "NEEDS_REVIEW"); ledger.checkpoint(entry.getKey(), c); continue; }
            c.addProperty("state", "APPLIED"); ledger.checkpoint(entry.getKey(), c);
            String escrow = c.get("escrow").getAsString();
            if (!escrow.isBlank()) ledger.section("escrow").getAsJsonObject(escrow).addProperty("state", c.get("finalState").getAsString());
            JsonObject op = entry.getValue().getAsJsonObject(); op.addProperty("state", "DONE"); op.addProperty("result", "重启核对物品操作已完成"); ledger.save();
        }
    }
    private static ItemStack[] cloneItems(ItemStack[] source) { ItemStack[] copy = new ItemStack[source.length]; for (int i=0;i<source.length;i++) copy[i]=source[i]==null?null:source[i].clone(); return copy; }
    private static void add(ItemStack[] target, ItemStack prototype, int amount) {
        int left=amount;
        for (int i=0;i<Math.min(36,target.length)&&left>0;i++) if (target[i]!=null&&target[i].isSimilar(prototype)) {
            int n=Math.min(left,prototype.getMaxStackSize()-target[i].getAmount()); if(n>0){target[i].setAmount(target[i].getAmount()+n);left-=n;}
        }
        for(int i=0;i<Math.min(36,target.length)&&left>0;i++) if(target[i]==null||target[i].getType().isAir()) {
            int n=Math.min(left,prototype.getMaxStackSize());target[i]=prototype.clone();target[i].setAmount(n);left-=n;
        }
        if(left>0) throw new IllegalArgumentException("背包空间不足，未给予或归还物品");
    }
    private static JsonArray encode(ItemStack[] items) { JsonArray a=new JsonArray();for(ItemStack item:items)a.add(item==null?"":Base64.getEncoder().encodeToString(item.serializeAsBytes()));return a; }
    private static ItemStack[] decode(JsonArray a) { ItemStack[] items=new ItemStack[a.size()];for(int i=0;i<a.size();i++){String s=a.get(i).getAsString();items[i]=s.isEmpty()?null:ItemStack.deserializeBytes(Base64.getDecoder().decode(s));}return items; }
}
