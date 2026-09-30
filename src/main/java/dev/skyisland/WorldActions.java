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
    private static boolean control(Material material) {
        return material==Material.COMMAND_BLOCK || material==Material.CHAIN_COMMAND_BLOCK || material==Material.REPEATING_COMMAND_BLOCK
            || material==Material.STRUCTURE_BLOCK || material==Material.JIGSAW;
    }
    private static void checkStructure(Structure structure) {
        if(structure.getPalettes().stream().flatMap(p->p.getBlocks().stream()).anyMatch(b->control(b.getType())))
            throw new IllegalArgumentException("快照包含控制类方块，不能通过恢复执行命令");
    }
    private final JavaPlugin plugin;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "skyisland-snapshot-io");
        t.setDaemon(true);
        return t;
    });
    private final Path snapshots;
    private boolean recoveryWaiting;
    private int editTick=-1,editBudget;
    private int reserveBlocks(int wanted) {
        int tick=Bukkit.getCurrentTick();if(tick!=editTick){editTick=tick;editBudget=128;}
        int allowed=Math.min(wanted,editBudget);editBudget-=allowed;return allowed;
    }

    WorldActions(JavaPlugin plugin) {
        this.plugin = plugin;
        snapshots = plugin.getDataFolder().toPath().resolve("snapshots");
        try { Files.createDirectories(snapshots); }
        catch (IOException e) { throw new IllegalStateException("无法创建快照目录", e); }
        recoverUnfinished();
        Bukkit.getScheduler().runTaskTimer(plugin,()->{if(recoveryWaiting)recoverUnfinished();},20L*60,20L*60);
    }

    record Prepared(String id, JsonObject action, String reason) {}

    Prepared prepare(JsonObject action) {
        String type = string(action, "type");
        String id = UUID.randomUUID().toString().substring(0, 8);
        return switch (type) {
            case "set_time", "add_time", "world_query", "set_weather", "set_gamerule", "set_border", "teleport",
                "spawn_entity", "remove_entity" -> new Prepared(id, action.deepCopy(), "");
            case "set_blocks" -> new Prepared(id, action.deepCopy(), partitions(action).size() > 1 ? "PARTITIONED" : complexReason(action));
            default -> throw new IllegalArgumentException("不支持的世界动作: " + type);
        };
    }

    void execute(Prepared prepared, Consumer<String> report) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("世界动作必须在主线程执行");
        try {
            JsonObject a = prepared.action();
            String result = switch (string(a, "type")) {
                case "set_time" -> time(a);
                case "add_time" -> { World w = world(a); w.setTime(w.getTime() + number(a, "ticks", 0, 23999)); yield "世界时间已增加"; }
                case "world_query" -> "世界=" + world(a).getName() + " time=" + world(a).getTime() + " fullTime=" + world(a).getFullTime();
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
        w.setThundering(a.has("thunder") && a.get("thunder").getAsBoolean());
        return w.getName() + (storm ? " 开始下雨" : " 天气转晴");
    }

    private String gameRule(JsonObject a) {
        World w = world(a);
        String rule = string(a, "rule");
        if (rule.equals("doDaylightCycle")) w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, a.get("value").getAsBoolean());
        else if (rule.equals("doWeatherCycle")) w.setGameRule(GameRule.DO_WEATHER_CYCLE, a.get("value").getAsBoolean());
        else if (rule.equals("doMobSpawning")) w.setGameRule(GameRule.DO_MOB_SPAWNING, a.get("value").getAsBoolean());
        else if (rule.equals("randomTickSpeed")) w.setGameRule(GameRule.RANDOM_TICK_SPEED, (int) number(a, "value", 0, 20));
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
        if(player.hasPermission("skyisland.admin"))throw new IllegalArgumentException("管理员不受强制传送");
        Location destination = location(a);
        if (!destination.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4))
            throw new IllegalArgumentException("目的地区块未加载");
        if (!safeLanding(destination))
            throw new IllegalArgumentException("目的地不安全");
        if(!player.teleport(destination))throw new IllegalArgumentException("传送被服务器事件取消，未完成");
        return player.getName() + " 已传送至 " + destination.getWorld().getName();
    }

    static boolean harmfulLanding(Material material) {
        return EnumSet.of(Material.MAGMA_BLOCK,Material.CAMPFIRE,Material.SOUL_CAMPFIRE,Material.FIRE,Material.SOUL_FIRE,
            Material.LAVA,Material.WATER,Material.CACTUS,Material.SWEET_BERRY_BUSH,Material.WITHER_ROSE,Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE,Material.NETHER_PORTAL,Material.END_PORTAL,Material.END_GATEWAY).contains(material);
    }
    private boolean safeLanding(Location at) {
        World world=at.getWorld();
        for(int x=(int)Math.floor(at.getX()-.3);x<=(int)Math.floor(at.getX()+.3);x++)for(int z=(int)Math.floor(at.getZ()-.3);z<=(int)Math.floor(at.getZ()+.3);z++) {
            if(!world.isChunkLoaded(x>>4,z>>4) || !world.getWorldBorder().isInside(new Location(world,x+.5,at.getY(),z+.5)))return false;
            Material floor=world.getBlockAt(x,at.getBlockY()-1,z).getType();if(!floor.isSolid() || harmfulLanding(floor))return false;
            for(int y=at.getBlockY();y<=(int)Math.floor(at.getY()+1.8);y++) {
                Block body=world.getBlockAt(x,y,z);if(!body.isPassable() || harmfulLanding(body.getType()))return false;
            }
        }
        return true;
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
        int actual=0;
        for (int i = 0; i < count; i++) if(at.getWorld().spawnEntity(at, type).isValid())actual++;
        if(actual==0)throw new IllegalArgumentException("实体生成未加入世界，可能被其他插件取消；实际数量=0");
        return "已生成 " + actual + " 个 " + type + "；请求="+count+"；未加入世界="+(count-actual);
    }

    private String remove(JsonObject a) {
        UUID id = UUID.fromString(string(a, "uuid"));
        Entity entity = Bukkit.getEntity(id);
        if (entity == null) throw new IllegalArgumentException("实体不存在");
        if (!(entity instanceof Monster || entity instanceof Item) || entity.getCustomName() != null
            || entity instanceof Tameable || !entity.getScoreboardTags().isEmpty()
            || !entity.getPersistentDataContainer().getKeys().isEmpty() || !entity.getPassengers().isEmpty()
            || entity.getVehicle() != null || entity instanceof org.bukkit.entity.LivingEntity living && (living.isLeashed() || !living.getRemoveWhenFarAway())
            || entity instanceof Item item && (item.getOwner() != null || item.getThrower() != null || item.getItemStack().hasItemMeta())) throw new IllegalArgumentException("仅可移除未命名的怪物或掉落物");
        entity.remove();
        return "已移除实体 " + id;
    }

    private String complexReason(JsonObject a) {
        Region r = region(a);
        Material target = material(a);
        if (FORBIDDEN.contains(target)) throw new IllegalArgumentException("目标方块被禁止");
        for(int x=r.x1;x<=r.x2;x++)for(int y=r.y1;y<=r.y2;y++)for(int z=r.z1;z<=r.z2;z++)
            if(control(r.world.getBlockAt(x,y,z).getType()))throw new IllegalArgumentException("区域含控制类方块，禁止通过编辑或恢复间接执行命令");
        if (!SIMPLE.contains(target)) return "目标方块或其物理行为复杂";
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
        java.util.List<JsonObject> parts = partitions(p.action());
        if (parts.size() > 1) { blockPart(p.id(), parts, 0, report); return; }
        if (p.reason().isEmpty() && !complexReason(p.action()).isEmpty())
            throw new IllegalArgumentException("区域发生变化，需要重新审批");
        Path existing = snapshots.resolve(p.id() + ".properties");
        if (Files.exists(existing)) {
            Properties saved = new Properties(); try (var in = Files.newInputStream(existing)) { saved.load(in); }
            if ("APPLIED".equals(saved.getProperty("state"))) { report.accept("此编辑已完成；撤销 ID: " + p.id()); return; }
            if("APPLYING".equals(saved.getProperty("state")) && "true".equals(saved.getProperty("paused")) && "2".equals(saved.getProperty("signature-version"))) {
                Region r=Region.fromMetadata(saved);
                if(!signature(r).equals(saved.getProperty("expected")))throw new IllegalArgumentException("暂停后区域已变化，需重新调查，未恢复执行");
                scheduleEdit(p.id(),saved,r,material(p.action()),Integer.parseInt(saved.getProperty("cursor")),report);return;
            }
            throw new IllegalArgumentException("此操作已有未完成或已撤销快照，需核查 " + p.id());
        }
        Region r = region(p.action());
        Material target = material(p.action());
        String before = signature(r);
        Structure structure = Bukkit.getStructureManager().createStructure();
        structure.fill(new Location(r.world, r.x1, r.y1, r.z1),
            new BlockVector(r.x2 - r.x1 + 1, r.y2 - r.y1 + 1, r.z2 - r.z1 + 1), false);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Bukkit.getStructureManager().saveStructure(buffer, structure);
        byte[] snapshot = buffer.toByteArray();
        java.util.List<byte[]> rows = new java.util.ArrayList<>();
        java.util.List<String> rowCoords = new java.util.ArrayList<>();
        for(int x=r.x1;x<=r.x2;x+=16)for(int y=r.y1;y<=r.y2;y++)for(int z=r.z1;z<=r.z2;z+=8) {
            int width=Math.min(16,r.x2-x+1),depth=Math.min(8,r.z2-z+1);
            Structure row=Bukkit.getStructureManager().createStructure();
            row.fill(new Location(r.world,x,y,z),new BlockVector(width,1,depth),false);
            ByteArrayOutputStream rowBuffer=new ByteArrayOutputStream();Bukkit.getStructureManager().saveStructure(rowBuffer,row);
            rows.add(rowBuffer.toByteArray());rowCoords.add(x+","+y+","+z);
        }
        Properties metadata = new Properties();
        metadata.setProperty("world", r.world.getUID().toString());
        metadata.setProperty("coords", r.x1 + "," + r.y1 + "," + r.z1 + "," + r.x2 + "," + r.y2 + "," + r.z2);
        metadata.setProperty("before", before);
        metadata.setProperty("target", target.name());
        metadata.setProperty("state", "PREPARED");
        metadata.setProperty("signature-version","2");
        metadata.setProperty("rollbackRows",String.join(";",rowCoords));
        io.execute(() -> {
            try {
                durableWrite(snapshots.resolve(p.id() + ".nbt"), snapshot);
                for(int row=0;row<rows.size();row++)durableWrite(snapshots.resolve(p.id()+"-row"+row+".nbt"),rows.get(row));
                ByteArrayOutputStream props = new ByteArrayOutputStream();
                metadata.store(props, "SkyIslandSystem world edit");
                durableWrite(snapshots.resolve(p.id() + ".properties"), props.toByteArray());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                    if (!Objects.equals(before, signature(r))) {
                        metadata.setProperty("state", "CANCELLED");
                        try { writePropertiesStrict(p.id(), metadata); }
                        catch (IOException error) { plugin.getLogger().severe("编辑取消状态写入失败: " + p.id()); }
                        report.accept("世界已被其他行为修改，取消编辑 " + p.id());
                        return;
                    }
                    metadata.setProperty("state", "APPLYING");
                    try { writePropertiesStrict(p.id(), metadata); }
                    catch (IOException error) { report.accept("无法持久记录编辑开始，未编辑世界: " + error.getMessage()); return; }
                    metadata.setProperty("expected",before);metadata.setProperty("cursor","0");
                    scheduleEdit(p.id(),metadata,r,target,0,report);
                    } catch (RuntimeException failure) {
                        metadata.setProperty("state", "CANCELLED");
                        try { writePropertiesStrict(p.id(),metadata); }
                        catch (IOException error) { plugin.getLogger().severe("编辑取消状态写入失败: " + p.id()); }
                        report.accept("编辑停止，快照保存后区域无法核验；未开始编辑；原因=" + failure.getMessage());
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> report.accept("快照写入失败，未编辑世界: " + e.getMessage()));
            }
        });
    }

    private boolean paused() { return plugin instanceof SkyIslandPlugin sky && sky.aiPaused(); }

    private void scheduleEdit(String id,Properties metadata,Region r,Material target,int start,Consumer<String> report) {
        if(start<0 || start>=r.volume())throw new IllegalArgumentException("编辑游标无效，需重新调查");
        java.util.List<Block> blocks=new java.util.ArrayList<>();
        for(int x=r.x1;x<=r.x2;x++)for(int y=r.y1;y<=r.y2;y++)for(int z=r.z1;z<=r.z2;z++)blocks.add(r.world.getBlockAt(x,y,z));
        int[] cursor={start};String[] expected={signature(r)};
        Bukkit.getScheduler().runTaskTimer(plugin,task->{
            try {
                if(paused()) {
                    metadata.setProperty("paused","true");metadata.setProperty("cursor",Integer.toString(cursor[0]));metadata.setProperty("expected",expected[0]);
                    writePropertiesStrict(id,metadata);task.cancel();report.accept("任务已暂停；编辑游标="+cursor[0]+"；恢复记录="+id);return;
                }
                if(!expected[0].equals(signature(r)))throw new IllegalStateException("玩家或世界并发修改了区域");
                int count=reserveBlocks(Math.min(128,blocks.size()-cursor[0]));if(count==0)return;
                int end=cursor[0]+count;while(cursor[0]<end)blocks.get(cursor[0]++).setType(target,false);
                expected[0]=signature(r);if(cursor[0]<blocks.size())return;
                task.cancel();metadata.setProperty("state","APPLIED");metadata.setProperty("after",expected[0]);metadata.remove("paused");
                writePropertiesStrict(id,metadata);report.accept("已编辑 "+r.volume()+" 个方块；撤销 ID: "+id);
            }catch(Exception failure) {
                task.cancel();metadata.setProperty("state","NEEDS_REVIEW");
                try{writePropertiesStrict(id,metadata);}catch(IOException error){plugin.getLogger().severe("编辑冲突无法记录 "+id);}
                report.accept("编辑停止，区域冲突需复查；恢复材料="+id+"；原因="+failure.getMessage());
            }
        },1L,1L);
    }

    private void blockPart(String parent, java.util.List<JsonObject> parts, int cursor, Consumer<String> report) {
        if (cursor == parts.size()) { java.util.List<String> ids=new java.util.ArrayList<>();
            for(int i=0;i<parts.size();i++)try{ids.add(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((parent+":"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,8));}catch(Exception impossible){throw new IllegalStateException(impossible);}
            report.accept("分区编辑已完成；任务=" + parent + "，子撤销ID="+ids); return; }
        try {
            String child = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((parent + ":" + cursor).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,8);
            String reason = complexReason(parts.get(cursor));
            if (!reason.isEmpty() && !recentBackup()) throw new IllegalArgumentException("复杂分区缺少结构可识别的近期备份");
            Prepared prepared = new Prepared(child, parts.get(cursor), reason);
            blocks(prepared, result -> {
                plugin.getLogger().info("区域任务 " + parent + " 子快照=" + child + "：" + result);
                if (result.startsWith("已编辑") || result.startsWith("此编辑已完成")) blockPart(parent, parts, cursor + 1, report);
                else if(result.startsWith("任务已暂停"))report.accept(result+"；分区任务="+parent);
                else report.accept("分区任务停止；任务=" + parent + " 子快照=" + child + "；" + result);
            });
        } catch (Exception failure) { report.accept("分区任务停止：" + failure.getMessage()); }
    }

    private java.util.List<JsonObject> partitions(JsonObject a) {
        World w = world(a);
        int x1=(int)number(a,"x1",-29999984,29999984), x2=(int)number(a,"x2",-29999984,29999984);
        int y1=(int)number(a,"y1",w.getMinHeight(),w.getMaxHeight()-1), y2=(int)number(a,"y2",w.getMinHeight(),w.getMaxHeight()-1);
        int z1=(int)number(a,"z1",-29999984,29999984), z2=(int)number(a,"z2",-29999984,29999984);
        if(x1>x2||y1>y2||z1>z2) throw new IllegalArgumentException("区域起点须不大于终点");
        long pieces=(((long)x2-x1+16)/16)*(((long)y2-y1+16)/16)*(((long)z2-z1+16)/16);
        if(pieces>256) throw new IllegalArgumentException("区域超过 256 份快照，请拆成多个治理任务");
        java.util.List<JsonObject> result=new java.util.ArrayList<>();
        for(int x=x1;x<=x2;x+=16)for(int y=y1;y<=y2;y+=16)for(int z=z1;z<=z2;z+=16){
            JsonObject part=a.deepCopy(); part.addProperty("x1",x);part.addProperty("x2",Math.min(x+15,x2));
            part.addProperty("y1",y);part.addProperty("y2",Math.min(y+15,y2));part.addProperty("z1",z);part.addProperty("z2",Math.min(z+15,z2));result.add(part);
        }
        return result;
    }

    void undo(String id, Consumer<String> report) {
        if (!id.matches("[0-9a-f]{8}")) { report.accept("撤销 ID 无效"); return; }
        io.execute(() -> {
            try {
                Properties metadata = new Properties();
                try (var in = Files.newInputStream(snapshots.resolve(id + ".properties"))) { metadata.load(in); }
                byte[] bytes = Files.readAllBytes(snapshots.resolve(id + ".nbt"));
                java.util.List<byte[]> rows=new java.util.ArrayList<>();
                if(metadata.containsKey("rollbackRows"))for(int row=0;row<metadata.getProperty("rollbackRows").split(";").length;row++)
                    rows.add(Files.readAllBytes(snapshots.resolve(id+"-row"+row+".nbt")));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        boolean resume="ROLLING_BACK".equals(metadata.getProperty("state")) && "true".equals(metadata.getProperty("paused"));
                        if (!"APPLIED".equals(metadata.getProperty("state")) && !resume)
                            throw new IllegalArgumentException("此记录未确认已执行，请人工检查");
                        Region r = Region.fromMetadata(metadata);
                        Structure original = Bukkit.getStructureManager().loadStructure(new ByteArrayInputStream(bytes));
                        if(!"2".equals(metadata.getProperty("signature-version")) && original.getPalettes().stream().flatMap(p->p.getBlocks().stream()).anyMatch(b->b instanceof TileState))
                            throw new IllegalArgumentException("旧版复杂快照缺少完整状态指纹，需依据外部备份核查；未覆盖世界");
                        if (!Objects.equals(metadata.getProperty(resume?"expected":"after"), signature(r)))
                            throw new IllegalArgumentException("编辑后区域又被修改，拒绝覆盖玩家变化");
                        if(!rows.isEmpty()) { undoRows(id,metadata,r,rows,report);return; }
                        if(paused()){metadata.setProperty("paused","true");writePropertiesStrict(id,metadata);report.accept("任务已暂停；撤销尚未开始；恢复记录="+id);return;}
                        checkStructure(original);
                        metadata.setProperty("state", "ROLLING_BACK");
                        writePropertiesStrict(id, metadata);
                        original.place(new Location(r.world, r.x1, r.y1, r.z1), false,
                            org.bukkit.block.structure.StructureRotation.NONE, org.bukkit.block.structure.Mirror.NONE,
                            0, 1.0f, new java.util.Random(0));
                        if (!Objects.equals(metadata.getProperty("before"), signature(r))) {
                            metadata.setProperty("state", "NEEDS_REVIEW");
                            writePropertiesStrict(id, metadata);
                            throw new IllegalStateException("快照恢复后方块状态不一致，需人工核查");
                        }
                        metadata.setProperty("state", "ROLLED_BACK");
                        writePropertiesStrict(id, metadata);
                        report.accept("已恢复方块快照 " + id + "；邻块物理变化不在快照范围内");
                    } catch (Exception e) { report.accept("无法撤销: " + e.getMessage()); }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> report.accept("读取快照失败: " + e.getMessage()));
            }
        });
    }

    private void undoRows(String id,Properties metadata,Region r,java.util.List<byte[]> rows,Consumer<String> report) throws IOException {
        metadata.setProperty("state","ROLLING_BACK");writePropertiesStrict(id,metadata);
        String[] locations=metadata.getProperty("rollbackRows").split(";");int[] cursor={"true".equals(metadata.getProperty("paused"))?Integer.parseInt(metadata.getProperty("cursor","0")):0};String[] expected={signature(r)};
        if(cursor[0]<0 || cursor[0]>=rows.size())throw new IllegalArgumentException("恢复游标无效");
        Bukkit.getScheduler().runTaskTimer(plugin,task->{
            try {
                if(paused()) {metadata.setProperty("paused","true");metadata.setProperty("cursor",Integer.toString(cursor[0]));metadata.setProperty("expected",expected[0]);writePropertiesStrict(id,metadata);task.cancel();report.accept("任务已暂停；撤销游标="+cursor[0]+"；恢复记录="+id);return;}
                for(int x=r.x1>>4;x<=r.x2>>4;x++)for(int z=r.z1>>4;z<=r.z2>>4;z++)if(!r.world.isChunkLoaded(x,z))throw new IllegalStateException("恢复区块已卸载");
                if(!expected[0].equals(signature(r)))throw new IllegalStateException("恢复时玩家或世界并发修改");
                int[] at=Arrays.stream(locations[cursor[0]].split(",")).mapToInt(Integer::parseInt).toArray();
                int count=Math.min(16,r.x2-at[0]+1)*Math.min(8,r.z2-at[2]+1);
                if(editTick==Bukkit.getCurrentTick() && editBudget<count)return;
                reserveBlocks(count);
                Structure row=Bukkit.getStructureManager().loadStructure(new ByteArrayInputStream(rows.get(cursor[0]++)));
                checkStructure(row);
                row.place(new Location(r.world,at[0],at[1],at[2]),false,org.bukkit.block.structure.StructureRotation.NONE,org.bukkit.block.structure.Mirror.NONE,0,1.0f,new java.util.Random(0));
                expected[0]=signature(r);if(cursor[0]<rows.size())return;task.cancel();
                if(!expected[0].equals(metadata.getProperty("before")))throw new IllegalStateException("恢复结果与快照不一致");
                metadata.setProperty("state","ROLLED_BACK");metadata.remove("paused");writePropertiesStrict(id,metadata);report.accept("已分批恢复快照 "+id+"；每 tick 最多128方块");
            }catch(Exception failure){task.cancel();metadata.setProperty("state","NEEDS_REVIEW");try{writePropertiesStrict(id,metadata);}catch(IOException ignored){plugin.getLogger().severe("无法记录恢复冲突 "+id);}report.accept("恢复停止，保留快照 "+id+"："+failure.getMessage());}
        },1L,1L);
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
        Path dir = Path.of(path).toAbsolutePath().normalize();
        for (World world : Bukkit.getWorlds())
            if (world.getWorldFolder().toPath().toAbsolutePath().normalize().getParent().equals(dir)) return false;
        return BackupVerifier.recent(dir);
    }

    private void recoverUnfinished() {
        recoveryWaiting=false;
        try (var files = Files.list(snapshots)) {
            files.filter(path -> path.getFileName().toString().matches("[0-9a-f]{8}\\.properties"))
                .forEach(path -> {
                    String id = path.getFileName().toString().replace(".properties", "");
                    try {
                        Properties metadata = new Properties();
                        try (var in = Files.newInputStream(path)) { metadata.load(in); }
                        String phase = metadata.getProperty("state");
                        if (!"APPLYING".equals(phase) && !"ROLLING_BACK".equals(phase)) return;
                        Region r = Region.fromMetadata(metadata);
                        if (r.volume() > 4096)
                            throw new IllegalArgumentException("区域超限");
                        for (int x = r.x1 >> 4; x <= r.x2 >> 4; x++)
                            for (int z = r.z1 >> 4; z <= r.z2 >> 4; z++)
                                if (!r.world.isChunkLoaded(x, z)) { recoveryWaiting=true;return; }
                        String current = signature(r);
                        if("true".equals(metadata.getProperty("paused")) && "2".equals(metadata.getProperty("signature-version"))) {
                            if(!current.equals(metadata.getProperty("expected"))){metadata.setProperty("state","NEEDS_REVIEW");writePropertiesStrict(id,metadata);}
                            return;
                        }
                        if ("ROLLING_BACK".equals(phase)) {
                            metadata.setProperty("state", current.equals(metadata.getProperty("before")) ? "ROLLED_BACK"
                                : current.equals(metadata.getProperty("after")) ? "APPLIED" : "NEEDS_REVIEW");
                        } else if (current.equals(metadata.getProperty("before"))) metadata.setProperty("state", "CANCELLED");
                        else {
                            Material target = Material.matchMaterial(metadata.getProperty("target", ""));
                            boolean complete = target != null;
                            for (int x = r.x1; x <= r.x2; x++) for (int y = r.y1; y <= r.y2; y++)
                                for (int z = r.z1; z <= r.z2; z++)
                                    if (r.world.getBlockAt(x, y, z).getType() != target) complete = false;
                            metadata.setProperty("state", complete ? "APPLIED" : "NEEDS_REVIEW");
                            if (complete) metadata.setProperty("after", current);
                        }
                        writePropertiesStrict(id, metadata);
                        plugin.getLogger().warning("已核对中断编辑 " + id + "：" + metadata.getProperty("state"));
                    } catch (Exception uncertain) {
                        plugin.getLogger().severe("中断编辑 " + id + " 状态无法自动核对，请人工检查：" + uncertain.getMessage());
                    }
                });
        } catch (IOException error) { throw new IllegalStateException("无法检查未完成世界编辑", error); }
    }

    private void writePropertiesStrict(String id, Properties p) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.store(out, "SkyIslandSystem world edit");
        durableWrite(snapshots.resolve(id + ".properties"), out.toByteArray());
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
        for(int x=r.x1>>4;x<=r.x2>>4;x++)for(int z=r.z1>>4;z<=r.z2>>4;z++)if(!r.world.isChunkLoaded(x,z))throw new IllegalStateException("区域区块已卸载");
        StringBuilder result = new StringBuilder();boolean tile=false;
        for (int x = r.x1; x <= r.x2; x++) for (int y = r.y1; y <= r.y2; y++)
            for (int z = r.z1; z <= r.z2; z++) {
                Block block = r.world.getBlockAt(x, y, z);
                result.append(block.getBlockData().getAsString()).append(';');
                if(block.getState() instanceof TileState)tile=true;
                if (block.getState() instanceof Container container) {
                    for (ItemStack item : container.getSnapshotInventory().getContents())
                        result.append(item == null ? "empty" : item.serialize().toString()).append(';');
                }
            }
        try {
            if(tile) {
                Structure state=Bukkit.getStructureManager().createStructure();
                state.fill(new Location(r.world,r.x1,r.y1,r.z1),new BlockVector(r.x2-r.x1+1,r.y2-r.y1+1,r.z2-r.z1+1),false);
                ByteArrayOutputStream data=new ByteArrayOutputStream();Bukkit.getStructureManager().saveStructure(data,state);
                result.append(java.util.Base64.getEncoder().encodeToString(data.toByteArray()));
            }
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("无法完整核对方块状态，停止修改",failure); }
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
            || r.volume() > 4096)
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

    static long number(JsonObject a, String key, long min, long max) {
        if (!a.has(key) || !a.get(key).isJsonPrimitive() || !a.get(key).getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("缺少数值字段 " + key);
        long value;
        try { value = a.get(key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(key + " 须为范围内整数"); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }

    static double number(JsonObject a, String key, double min, double max) {
        if (!a.has(key) || !a.get(key).isJsonPrimitive() || !a.get(key).getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("缺少数值字段 " + key);
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
