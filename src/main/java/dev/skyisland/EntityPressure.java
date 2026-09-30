package dev.skyisland;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Counts loaded chunks only. The case ID identifies a place, never a list of entities to trust later. */
final class EntityPressure {
    enum Kind {
        MONSTER(80, AgentRole.NABERIUS), ANIMAL(100, AgentRole.NABERIUS), OLD_ITEM(120, AgentRole.RONOVA);
        final int threshold;
        final AgentRole owner;
        Kind(int threshold, AgentRole owner) { this.threshold = threshold; this.owner = owner; }
    }

    record Key(UUID world, int x, int z, Kind kind) {}
    record Incident(String id, Key key, int count, boolean emergency, long updatedAt) {
        AgentRole owner() { return key.kind.owner; }
    }
    private record ChunkRef(UUID world, int x, int z) {}
    private record Sample(int count, int streak, long seenAt) {}

    private final JavaPlugin plugin;
    private final Path quotaFile;
    private final Path caseFile;
    private final Consumer<Incident> opened;
    private final Consumer<String> audit;
    private final Map<Key, Sample> samples = new HashMap<>();
    private final Map<Key, Incident> cases = new HashMap<>();
    private final Map<String, Key> byId = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private final Map<String, Integer> caseRemoved = new HashMap<>();
    private final Map<UUID, Deque<Long>> hourlyRemovals = new HashMap<>();
    private final Deque<ChunkRef> scan = new ArrayDeque<>();
    private final Set<Key> seen = new HashSet<>();
    private long nextScan;
    private int lowTpsSamples;
    private int removalTick=-1,removalBudget;

    EntityPressure(JavaPlugin plugin, Consumer<Incident> opened, Consumer<String> audit) {
        this.plugin = plugin;
        quotaFile = plugin.getDataFolder().toPath().resolve("entity-quota.properties");
        caseFile = plugin.getDataFolder().toPath().resolve("entity-cases.json");
        this.opened = opened;
        this.audit = audit;
        JsonObject saved=JsonState.read(caseFile);
        for(var entry:saved.entrySet()){JsonObject c=entry.getValue().getAsJsonObject();Key key=new Key(UUID.fromString(c.get("world").getAsString()),c.get("x").getAsInt(),c.get("z").getAsInt(),Kind.valueOf(c.get("kind").getAsString()));Incident incident=new Incident(entry.getKey(),key,c.get("count").getAsInt(),false,0);cases.put(key,incident);byId.put(incident.id(),key);}
        if (Files.exists(quotaFile)) {
            Properties data = new Properties();
            try (InputStream in = Files.newInputStream(quotaFile)) {
                data.load(in);
                for (String key : data.stringPropertyNames()) {
                    Deque<Long> times = new ArrayDeque<>();
                    String value = data.getProperty(key);
                    if (!value.isBlank()) for (String part : value.split(",")) times.addLast(Long.parseLong(part));
                    hourlyRemovals.put(UUID.fromString(key), times);
                }
            } catch (Exception invalid) { throw new IllegalStateException("实体清理配额记录损坏，请从备份恢复", invalid); }
        }
    }

    void tick() {
        long now = System.currentTimeMillis();
        if (scan.isEmpty()) {
            if (now < nextScan) return;
            nextScan = now + 60_000L;
            lowTpsSamples = Bukkit.getTPS()[0] < 15 ? lowTpsSamples + 1 : 0;
            seen.clear();
            for (World world : Bukkit.getWorlds())
                for (Chunk chunk : world.getLoadedChunks()) scan.add(new ChunkRef(world.getUID(), chunk.getX(), chunk.getZ()));
        }
        for (int i = 0; i < 4 && !scan.isEmpty(); i++) {
            ChunkRef ref = scan.removeFirst();
            World world = Bukkit.getWorld(ref.world);
            if (world != null && world.isChunkLoaded(ref.x, ref.z)) inspect(world.getChunkAt(ref.x, ref.z), now);
        }
        if (scan.isEmpty()) {
            samples.keySet().removeIf(key -> !seen.contains(key));
            cases.keySet().removeIf(key -> {
                if (seen.contains(key)) return false;
                byId.remove(cases.get(key).id());
                caseRemoved.remove(cases.get(key).id());
                audit.accept("entity-case-closed id=" + cases.get(key).id() + " reason=chunk-unloaded-or-pressure-ended");
                return true;
            });
            saveCases();
        }
    }

    private void inspect(Chunk chunk, long now) {
        int monsters = 0, animals = 0, oldItems = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Monster) monsters++;
            else if (entity instanceof org.bukkit.entity.Animals) animals++;
            else if (entity instanceof Item item && item.getTicksLived() >= 4_800) oldItems++;
        }
        count(chunk, Kind.MONSTER, monsters, now);
        count(chunk, Kind.ANIMAL, animals, now);
        count(chunk, Kind.OLD_ITEM, oldItems, now);
    }

    private void count(Chunk chunk, Kind kind, int count, long now) {
        Key key = new Key(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), kind);
        seen.add(key);
        if (count < kind.threshold) {
            samples.remove(key);
            Incident previous = cases.remove(key);
            if (previous != null) { byId.remove(previous.id()); caseRemoved.remove(previous.id()); audit.accept("entity-case-closed id=" + previous.id() + " reason=pressure-ended"); }
            return;
        }
        Sample previous = samples.get(key);
        int streak = previous == null || now - previous.seenAt > 180_000L ? 1 : previous.streak + 1;
        samples.put(key, new Sample(count, streak, now));
        if (streak < 2) return;
        boolean emergency = kind != Kind.ANIMAL && count >= kind.threshold * 3 && lowTpsSamples >= 3;
        Incident existing = cases.get(key);
        if (existing == null) {
            String id;
            do{id=UUID.randomUUID().toString().substring(0,8);}while(byId.containsKey(id) || plugin instanceof SkyIslandPlugin sky && sky.knownCase(id));
            Incident incident = new Incident(id, key, count, emergency, now);
            cases.put(key, incident);
            byId.put(incident.id(), key);
            audit.accept("entity-case-open id=" + incident.id() + " world=" + chunk.getWorld().getName()
                + " chunk=" + chunk.getX() + "," + chunk.getZ() + " kind=" + kind + " count=" + count + " emergency=" + emergency);
            opened.accept(incident);
        } else {
            Incident updated = new Incident(existing.id(), key, count, emergency, now);
            cases.put(key, updated);
            if (emergency && !existing.emergency()) opened.accept(updated);
        }
    }

    Incident require(String id) {
        Key key = byId.get(id);
        Incident incident = key == null ? null : cases.get(key);
        if (incident == null || System.currentTimeMillis() - incident.updatedAt() > 180_000L)
            throw new IllegalArgumentException("实体案件已失效，请重新巡查");
        World world = Bukkit.getWorld(key.world);
        if (world == null || !world.isChunkLoaded(key.x, key.z)) throw new IllegalArgumentException("案件区块已卸载");
        return incident;
    }

    private void saveCases(){JsonObject out=new JsonObject();for(Incident i:cases.values()){JsonObject c=new JsonObject();c.addProperty("world",i.key.world.toString());c.addProperty("x",i.key.x);c.addProperty("z",i.key.z);c.addProperty("kind",i.key.kind.name());c.addProperty("count",i.count);out.add(i.id,c);}JsonState.write(caseFile,out);}

    void validate(JsonObject action) {
        if (require(WorldActions.string(action, "incident_id")).key.kind == Kind.ANIMAL)
            throw new IllegalArgumentException("普通动物案件只上报，不允许自动清理");
    }

    void relieve(JsonObject action, AgentRole actor, Consumer<String> report) {
        Incident incident = require(WorldActions.string(action, "incident_id"));
        if (incident.key.kind == Kind.ANIMAL) throw new IllegalArgumentException("普通动物案件只上报，不允许自动清理");
        // The caller checks the fine capability, including a Phanes cross-duty grant.
        relieve(incident, actor.id, report);
    }

    void relieveAuto(String id, Consumer<String> report) {
        Incident incident = require(id);
        if (!incident.emergency()) throw new IllegalArgumentException("仅紧急实体案件允许插件自动清理");
        relieve(incident, "plugin", report);
    }

    private void relieve(Incident incident, String actor, Consumer<String> report) {
        if (!active.add(incident.id())) throw new IllegalArgumentException("此案件已有清理任务");
        World world = Bukkit.getWorld(incident.key.world);
        int[] removed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (plugin instanceof SkyIslandPlugin sky && sky.aiPaused()) return;
            Incident current;
            try { current = require(incident.id()); }
            catch (RuntimeException invalid) { task.cancel(); active.remove(incident.id()); report.accept("清理停止：" + invalid.getMessage()); return; }
            int count = 0;
            List<Entity> eligible = new ArrayList<>();
            for (Entity entity : world.getChunkAt(current.key.x, current.key.z).getEntities()) {
                if (matches(entity, current.key.kind)) {
                    count++;
                    if (safe(entity)) eligible.add(entity);
                }
            }
            if (count <= current.key.kind.threshold / 2 || eligible.isEmpty()
                || caseRemoved.getOrDefault(incident.id(), 0) >= 500 || quota(world.getUID()) >= 1_000) {
                task.cancel();
                active.remove(incident.id());
                report.accept("实体案件 " + incident.id() + " 清理结束：移除=" + removed[0]
                    + "，当前数量=" + count + "，受保护或不合格=" + Math.max(0, count - eligible.size()));
                return;
            }
            boolean nearPlayer = eligible.stream().anyMatch(this::nearPlayer);
            if (nearPlayer && !current.emergency()) {
                task.cancel();
                active.remove(incident.id());
                report.accept("实体案件 " + incident.id() + " 位于玩家 32 格内，非紧急状态只告警；已移除=" + removed[0]);
                return;
            }
            eligible.sort(Comparator.comparingInt(Entity::getTicksLived).reversed());
            int batch = Math.min(20, Math.min(500 - caseRemoved.getOrDefault(incident.id(), 0), 1_000 - quota(world.getUID())));
            int tick=Bukkit.getCurrentTick();if(tick!=removalTick){removalTick=tick;removalBudget=20;}
            batch=Math.min(batch,Math.min(eligible.size(),removalBudget));if(batch==0)return;removalBudget-=batch;
            Deque<Long> times = hourlyRemovals.computeIfAbsent(world.getUID(), ignored -> new ArrayDeque<>());
            for (int i = 0; i < batch; i++) times.addLast(System.currentTimeMillis());
            try { saveQuota(); }
            catch (RuntimeException failed) {
                for (int i = 0; i < batch; i++) times.removeLast();
                task.cancel(); active.remove(incident.id());
                report.accept("实体清理配额无法持久化，已停止；" + failed.getMessage());
                return;
            }
            try {
                audit.accept("entity-case-batch-start id=" + incident.id() + " actor=" + actor
                    + " reserved=" + batch + " candidate-uuids="
                    + eligible.stream().limit(batch).map(entity -> entity.getUniqueId().toString()).toList());
            } catch (RuntimeException failed) {
                task.cancel(); active.remove(incident.id());
                report.accept("实体清理审计写入失败，已停止");
                return;
            }
            int beforeBatch = removed[0];
            for (Entity entity : eligible) {
                if (batch-- <= 0) break;
                if (!entity.isValid() || !safe(entity) || nearPlayer(entity) && !current.emergency()) continue;
                entity.remove();
                removed[0]++;
            }
            int remaining = count - (removed[0] - beforeBatch);
            caseRemoved.merge(incident.id(), removed[0] - beforeBatch, Integer::sum);
            cases.put(current.key, new Incident(current.id(), current.key, remaining, current.emergency(), System.currentTimeMillis()));
            audit.accept("entity-case-batch id=" + incident.id() + " actor=" + actor + " removed=" + removed[0]);
            if (remaining <= current.key.kind.threshold / 2) {
                task.cancel(); active.remove(incident.id());
                cases.remove(current.key); byId.remove(incident.id()); caseRemoved.remove(incident.id());
                audit.accept("entity-case-closed id=" + incident.id() + " reason=pressure-relieved");
                report.accept("实体案件 " + incident.id() + " 清理结束：移除=" + removed[0] + "，当前数量=" + remaining);
            }
        }, 1L, 1L);
    }

    private int quota(UUID world) {
        Deque<Long> times = hourlyRemovals.computeIfAbsent(world, ignored -> new ArrayDeque<>());
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        while (!times.isEmpty() && times.peekFirst() < cutoff) times.removeFirst();
        return times.size();
    }

    private void saveQuota() {
        Properties data = new Properties();
        hourlyRemovals.forEach((world, times) -> data.setProperty(world.toString(),
            times.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")));
        Path temp = quotaFile.resolveSibling(quotaFile.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { data.store(out, "SkyIslandSystem entity removal quota"); }
            try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, quotaFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, quotaFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) { throw new IllegalStateException("无法保存实体清理配额", error); }
    }

    private static boolean matches(Entity entity, Kind kind) {
        return switch (kind) {
            case MONSTER -> entity instanceof Monster;
            case ANIMAL -> entity instanceof org.bukkit.entity.Animals;
            case OLD_ITEM -> entity instanceof Item && entity.getTicksLived() >= 4_800;
        };
    }

    private static boolean safe(Entity entity) {
        if (entity.getCustomName() != null || !entity.getScoreboardTags().isEmpty()
            || !entity.getPersistentDataContainer().getKeys().isEmpty()
            || !entity.getPassengers().isEmpty() || entity.getVehicle() != null || entity instanceof Tameable) return false;
        if (entity instanceof org.bukkit.entity.LivingEntity living
            && (living.isLeashed() || !living.getRemoveWhenFarAway())) return false;
        if (entity instanceof Item item) {
            ItemStack stack = item.getItemStack();
            return item.getOwner() == null && item.getThrower() == null && !stack.hasItemMeta();
        }
        return entity instanceof Monster;
    }

    private boolean nearPlayer(Entity entity) {
        for (Player player : entity.getWorld().getPlayers())
            if (player.getLocation().distanceSquared(entity.getLocation()) <= 32 * 32) return true;
        return false;
    }

    String describe(String id) {
        Incident incident = require(id);
        World world = Bukkit.getWorld(incident.key.world);
        int total = 0, eligible = 0, protectedCount = 0;
        for (Entity entity : world.getChunkAt(incident.key.x, incident.key.z).getEntities()) {
            if (!matches(entity, incident.key.kind)) continue;
            total++;
            if (safe(entity) && !nearPlayer(entity)) eligible++;
            else protectedCount++;
        }
        return "实体案件=" + id + " world=" + world.getName() + " chunk=" + incident.key.x + "," + incident.key.z
            + " kind=" + incident.key.kind + " count=" + total + " eligible=" + eligible
            + " protected-or-near-player=" + protectedCount + " emergency=" + incident.emergency();
    }

    String summary() {
        List<Incident> top = cases.values().stream().sorted(Comparator.comparingInt(Incident::count).reversed()).limit(5).toList();
        if (top.isEmpty()) return "实体热点=无；每 60 秒仅扫描已加载区块";
        StringBuilder out = new StringBuilder("实体热点：");
        for (Incident incident : top) {
            World world = Bukkit.getWorld(incident.key.world);
            out.append("\n案件=").append(incident.id()).append(" world=")
                .append(world == null ? "未知" : world.getName()).append(" chunk=")
                .append(incident.key.x).append(',').append(incident.key.z)
                .append(" kind=").append(incident.key.kind).append(" count=").append(incident.count)
                .append(" emergency=").append(incident.emergency);
        }
        return out.toString();
    }

    java.util.List<JsonObject> observations() {
        java.util.List<JsonObject> rows=new java.util.ArrayList<>();
        for(Incident incident:cases.values()) {
            World w=Bukkit.getWorld(incident.key.world);if(w==null||!w.isChunkLoaded(incident.key.x,incident.key.z))continue;
            JsonObject row=new JsonObject();row.addProperty("incident_id",incident.id);row.addProperty("world",w.getName());row.addProperty("chunk_x",incident.key.x);row.addProperty("chunk_z",incident.key.z);row.addProperty("target_type",incident.key.kind.name());row.addProperty("count",incident.count);row.addProperty("emergency",incident.emergency);row.addProperty("updated_at",incident.updatedAt);rows.add(row);
        }
        rows.sort(java.util.Comparator.comparing(r->r.get("incident_id").getAsString()));return rows;
    }
}
