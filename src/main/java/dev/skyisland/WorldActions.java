package dev.skyisland;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

final class WorldActions implements AutoCloseable {
    private static final EnumSet<Material> SIMPLE = EnumSet.of(Material.AIR, Material.STONE,
        Material.COBBLESTONE, Material.DIRT, Material.GRASS_BLOCK, Material.DEEPSLATE,
        Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.SANDSTONE, Material.BRICKS,
        Material.GLASS, Material.TUFF, Material.CALCITE);
    private static final EnumSet<Material> FORBIDDEN = EnumSet.of(Material.COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK,
        Material.JIGSAW, Material.BEDROCK, Material.END_PORTAL, Material.END_PORTAL_FRAME,
        Material.TNT, Material.LAVA, Material.WATER, Material.FIRE);
    private final JavaPlugin plugin;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "skyisland-snapshot-io");
        t.setDaemon(true);
        return t;
    });
    private final Path snapshots;

    WorldActions(JavaPlugin plugin) {
        this.plugin = plugin;
        snapshots = plugin.getDataFolder().toPath().resolve("snapshots");
        try { Files.createDirectories(snapshots); }
        catch (IOException e) { throw new IllegalStateException("无法创建快照目录", e); }
    }

    record Prepared(String id, JsonObject action, String reason) {}

    Prepared prepare(JsonObject action) {
        String type = string(action, "type");
        String id = UUID.randomUUID().toString().substring(0, 8);
        return switch (type) {
            case "set_time", "set_weather", "set_gamerule", "set_border", "teleport",
                "spawn_entity", "remove_entity" -> new Prepared(id, action.deepCopy(), "");
            case "set_blocks" -> new Prepared(id, action.deepCopy(), complexReason(action));
            default -> throw new IllegalArgumentException("不支持的世界动作: " + type);
        };
    }

    void execute(Prepared prepared, Consumer<String> report) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("世界动作必须在主线程执行");
        try {
            JsonObject a = prepared.action();
            String result = switch (string(a, "type")) {
                case "set_time" -> time(a);
                case "set_weather" -> weather(a);
                case "set_gamerule" -> gameRule(a);
                case "set_border" -> border(a);
                case "teleport" -> teleport(a);
                case "spawn_entity" -> spawn(a);
                case "remove_entity" -> remove(a);
                case "set_blocks" -> { blocks(prepared, report); yield null; }
                default -> throw new IllegalArgumentException("动作类型失效");
            };
            if (result != null) report.accept(result);
        } catch (Exception e) {
            report.accept("动作拒绝: " + e.getMessage());
        }
    }

    private String time(JsonObject a) {
        World w = world(a);
        long ticks = number(a, "ticks", 0, 23999);
        w.setTime(ticks);
        return w.getName() + " 时间设为 " + ticks;
    }

    private String weather(JsonObject a) {
        World w = world(a);
        boolean storm = a.get("storm").getAsBoolean();
        w.setStorm(storm);
        w.setThundering(false);
        return w.getName() + (storm ? " 开始下雨" : " 天气转晴");
    }

    private String gameRule(JsonObject a) {
        World w = world(a);
        String rule = string(a, "rule");
        if (rule.equals("doDaylightCycle")) w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, a.get("value").getAsBoolean());
        else if (rule.equals("doWeatherCycle")) w.setGameRule(GameRule.DO_WEATHER_CYCLE, a.get("value").getAsBoolean());
        else if (rule.equals("doMobSpawning")) w.setGameRule(GameRule.DO_MOB_SPAWNING, a.get("value").getAsBoolean());
        else if (rule.equals("randomTickSpeed")) w.setGameRule(GameRule.RANDOM_TICK_SPEED, (int) number(a, "value", 0, 3));
        else throw new IllegalArgumentException("未授权的游戏规则");
        return w.getName() + " 游戏规则 " + rule + " 已调整";
    }

    private String border(JsonObject a) {
        World w = world(a);
        double size = number(a, "size", 32, 60000000);
        w.getWorldBorder().setSize(size, 60);
        return w.getName() + " 边界目标直径 " + size + "，60 秒过渡";
    }

    private String teleport(JsonObject a) {
        Player player = Bukkit.getPlayerExact(string(a, "player"));
        if (player == null) throw new IllegalArgumentException("玩家不在线");
        Location destination = location(a);
        if (!destination.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4))
            throw new IllegalArgumentException("目的地区块未加载");
        if (!destination.getBlock().isPassable() || !destination.clone().add(0, 1, 0).getBlock().isPassable()
            || !destination.clone().add(0, -1, 0).getBlock().getType().isSolid()
            || !destination.getWorld().getWorldBorder().isInside(destination))
            throw new IllegalArgumentException("目的地不安全");
        player.teleport(destination);
        return player.getName() + " 已传送至 " + destination.getWorld().getName();
    }

    private String spawn(JsonObject a) {
        EntityType type = EntityType.valueOf(string(a, "entity").toUpperCase());
        if (!EnumSet.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SHEEP,
            EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.VILLAGER).contains(type))
            throw new IllegalArgumentException("实体类型不在安全白名单");
        int count = (int) number(a, "count", 1, 5);
        Location at = location(a);
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4))
            throw new IllegalArgumentException("生成区块未加载");
        for (int i = 0; i < count; i++) at.getWorld().spawnEntity(at, type);
        return "已生成 " + count + " 个 " + type;
    }

    private String remove(JsonObject a) {
        UUID id = UUID.fromString(string(a, "uuid"));
        Entity entity = Bukkit.getEntity(id);
        if (entity == null) throw new IllegalArgumentException("实体不存在");
        if (!(entity instanceof Monster || entity instanceof Item) || entity.getCustomName() != null
            || entity instanceof Tameable) throw new IllegalArgumentException("仅可移除未命名的怪物或掉落物");
        entity.remove();
        return "已移除实体 " + id;
    }

    private String complexReason(JsonObject a) {
        Region r = region(a);
        Material target = material(a);
        if (FORBIDDEN.contains(target)) throw new IllegalArgumentException("目标方块被禁止");
        if (!SIMPLE.contains(target) || target == Material.AIR) return "目标方块或其物理行为复杂";
        for (int x = r.x1; x <= r.x2; x++) for (int y = r.y1; y <= r.y2; y++)
            for (int z = r.z1; z <= r.z2; z++) {
                Block b = r.world.getBlockAt(x, y, z);
                if (b.getState() instanceof TileState || !SIMPLE.contains(b.getType()))
                    return "区域包含容器、红石、流体或其他复杂方块";
                for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                    org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
                    org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                    org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                    Block adjacent = b.getRelative(face);
                    if (!r.world.isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4))
                        return "相邻区块未加载";
                    Material neighbor = adjacent.getType();
                    if (!SIMPLE.contains(neighbor)) return "相邻区域存在可能发生物理连锁的方块";
                }
            }
        return "";
    }

    private void blocks(Prepared p, Consumer<String> report) throws IOException {
        if (p.reason().isEmpty() && !complexReason(p.action()).isEmpty())
            throw new IllegalArgumentException("区域发生变化，需要重新审批");
        Region r = region(p.action());
        Material target = material(p.action());
        String before = signature(r);
        Structure structure = Bukkit.getStructureManager().createStructure();
        structure.fill(new Location(r.world, r.x1, r.y1, r.z1),
            new BlockVector(r.x2 - r.x1 + 1, r.y2 - r.y1 + 1, r.z2 - r.z1 + 1), false);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Bukkit.getStructureManager().saveStructure(buffer, structure);
        byte[] snapshot = buffer.toByteArray();
        Properties metadata = new Properties();
        metadata.setProperty("world", r.world.getUID().toString());
        metadata.setProperty("coords", r.x1 + "," + r.y1 + "," + r.z1 + "," + r.x2 + "," + r.y2 + "," + r.z2);
        metadata.setProperty("before", before);
        metadata.setProperty("target", target.name());
        metadata.setProperty("state", "PREPARED");
        io.execute(() -> {
            try {
                durableWrite(snapshots.resolve(p.id() + ".nbt"), snapshot);
                ByteArrayOutputStream props = new ByteArrayOutputStream();
                metadata.store(props, "SkyIslandSystem world edit");
                durableWrite(snapshots.resolve(p.id() + ".properties"), props.toByteArray());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!Objects.equals(before, signature(r))) {
                        report.accept("世界已被其他行为修改，取消编辑 " + p.id());
                        return;
                    }
                    for (int x = r.x1; x <= r.x2; x++) for (int y = r.y1; y <= r.y2; y++)
                        for (int z = r.z1; z <= r.z2; z++) r.world.getBlockAt(x, y, z).setType(target, true);
                    metadata.setProperty("state", "APPLIED");
                    metadata.setProperty("after", signature(r));
                    io.execute(() -> writeProperties(p.id(), metadata));
                    report.accept("已编辑 " + r.volume() + " 个方块；撤销 ID: " + p.id());
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> report.accept("快照写入失败，未编辑世界: " + e.getMessage()));
            }
        });
    }

    void undo(String id, Consumer<String> report) {
        if (!id.matches("[0-9a-f]{8}")) { report.accept("撤销 ID 无效"); return; }
        io.execute(() -> {
            try {
                Properties metadata = new Properties();
                try (var in = Files.newInputStream(snapshots.resolve(id + ".properties"))) { metadata.load(in); }
                byte[] bytes = Files.readAllBytes(snapshots.resolve(id + ".nbt"));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!"APPLIED".equals(metadata.getProperty("state")))
                            throw new IllegalArgumentException("此记录未确认已执行，请人工检查");
                        Region r = Region.fromMetadata(metadata);
                        if (!Objects.equals(metadata.getProperty("after"), signature(r)))
                            throw new IllegalArgumentException("编辑后区域又被修改，拒绝覆盖玩家变化");
                        Structure original = Bukkit.getStructureManager().loadStructure(new ByteArrayInputStream(bytes));
                        original.place(new Location(r.world, r.x1, r.y1, r.z1), false,
                            org.bukkit.block.structure.StructureRotation.NONE, org.bukkit.block.structure.Mirror.NONE,
                            0, 1.0f, new java.util.Random(0));
                        metadata.setProperty("state", "ROLLED_BACK");
                        io.execute(() -> writeProperties(id, metadata));
                        report.accept("已恢复方块快照 " + id + "；邻块物理变化不在快照范围内");
                    } catch (Exception e) { report.accept("无法撤销: " + e.getMessage()); }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> report.accept("读取快照失败: " + e.getMessage()));
            }
        });
    }

    String latest() {
        File[] files = snapshots.toFile().listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null || files.length == 0) return "无";
        return Arrays.stream(files).max((a, b) -> Long.compare(a.lastModified(), b.lastModified()))
            .map(f -> f.getName().replace(".properties", "")).orElse("无");
    }

    boolean recentBackup() {
        String path = plugin.getConfig().getString("backup-directory", "");
        if (path.isBlank()) return false;
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null) return false;
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        return Arrays.stream(files).anyMatch(f -> f.isFile() && Instant.ofEpochMilli(f.lastModified()).isAfter(cutoff));
    }

    private void writeProperties(String id, Properties p) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.store(out, "SkyIslandSystem world edit");
            durableWrite(snapshots.resolve(id + ".properties"), out.toByteArray());
        } catch (IOException e) { plugin.getLogger().warning("审计记录写入失败: " + id); }
    }

    private static void durableWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String signature(Region r) {
        StringBuilder result = new StringBuilder();
        for (int x = r.x1; x <= r.x2; x++) for (int y = r.y1; y <= r.y2; y++)
            for (int z = r.z1; z <= r.z2; z++) {
                Block block = r.world.getBlockAt(x, y, z);
                result.append(block.getBlockData().getAsString()).append(';');
                if (block.getState() instanceof Container container) {
                    for (ItemStack item : container.getSnapshotInventory().getContents())
                        result.append(item == null ? "empty" : item.serialize().toString()).append(';');
                }
            }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private Region region(JsonObject a) {
        World w = world(a);
        int x1 = (int) number(a, "x1", -29999984, 29999984);
        int y1 = (int) number(a, "y1", w.getMinHeight(), w.getMaxHeight() - 1);
        int z1 = (int) number(a, "z1", -29999984, 29999984);
        int x2 = (int) number(a, "x2", -29999984, 29999984);
        int y2 = (int) number(a, "y2", w.getMinHeight(), w.getMaxHeight() - 1);
        int z2 = (int) number(a, "z2", -29999984, 29999984);
        Region r = new Region(w, x1, y1, z1, x2, y2, z2);
        if (r.x1 > r.x2 || r.y1 > r.y2 || r.z1 > r.z2
            || (long) r.x2 - r.x1 >= 8 || (long) r.y2 - r.y1 >= 8 || (long) r.z2 - r.z1 >= 8
            || r.volume() > plugin.getConfig().getInt("max-simple-blocks-per-action", 64))
            throw new IllegalArgumentException("方块区域超过每次操作上限");
        for (int x = r.x1 >> 4; x <= r.x2 >> 4; x++) for (int z = r.z1 >> 4; z <= r.z2 >> 4; z++)
            if (!w.isChunkLoaded(x, z)) throw new IllegalArgumentException("目标区块未加载，拒绝触发区块生成");
        return r;
    }

    private static Material material(JsonObject a) {
        Material m = Material.matchMaterial(string(a, "material"));
        if (m == null || !m.isBlock()) throw new IllegalArgumentException("无效方块材料");
        return m;
    }

    private static World world(JsonObject a) {
        World world = Bukkit.getWorld(string(a, "world"));
        if (world == null) throw new IllegalArgumentException("世界不存在");
        return world;
    }

    private static Location location(JsonObject a) {
        World w = world(a);
        double x = number(a, "x", -29999984, 29999984);
        double y = number(a, "y", w.getMinHeight(), w.getMaxHeight() - 1);
        double z = number(a, "z", -29999984, 29999984);
        return new Location(w, x, y, z);
    }

    static String string(JsonObject a, String key) {
        if (!a.has(key) || !a.get(key).isJsonPrimitive()) throw new IllegalArgumentException("缺少字段 " + key);
        return a.get(key).getAsString();
    }

    private static long number(JsonObject a, String key, long min, long max) {
        long value = a.get(key).getAsLong();
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }

    private static double number(JsonObject a, String key, double min, double max) {
        double value = a.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }

    private record Region(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        long volume() { return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1); }
        static Region fromMetadata(Properties p) {
            World world = Bukkit.getWorld(UUID.fromString(p.getProperty("world")));
            if (world == null) throw new IllegalArgumentException("原世界不存在");
            int[] c = Arrays.stream(p.getProperty("coords").split(",")).mapToInt(Integer::parseInt).toArray();
            return new Region(world, c[0], c[1], c[2], c[3], c[4], c[5]);
        }
    }

    @Override public void close() { io.shutdown(); }
}
