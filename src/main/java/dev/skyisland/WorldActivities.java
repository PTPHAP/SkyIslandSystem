package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/** Voluntary, measurable commissions. No scoreboard ownership or synthetic quest completion. */
final class WorldActivities implements Listener {
    interface Port {
        boolean authenticated(Player p);boolean paused();
        String reward(String operation,Player p,JsonObject item);
        String state(String operation);
        void restore(String operation,String edit);
        void announce(String text);
    }
    private final GovernanceLedger ledger;private final Port port;private long nextTick;private boolean editsDirty;
    WorldActivities(GovernanceLedger ledger,Port port){this.ledger=ledger;this.port=port;}
    static boolean simple(Block b){return !(b.getState() instanceof org.bukkit.block.TileState)&&Set.of(Material.AIR,Material.CAVE_AIR,Material.STONE,Material.COBBLESTONE,Material.DIRT,Material.GRASS_BLOCK,Material.DEEPSLATE,Material.OAK_PLANKS,Material.SPRUCE_PLANKS,Material.SANDSTONE,Material.BRICKS,Material.GLASS,Material.TUFF,Material.CALCITE,Material.GRASS,Material.TALL_GRASS).contains(b.getType());}
    static boolean inside(JsonObject zone,Location p){if(zone==null||!zone.has("world")||!zone.get("world").getAsString().equals(p.getWorld().getName()))return false;for(String axis:List.of("x","y","z")){double v=axis.equals("x")?p.getX():axis.equals("y")?p.getY():p.getZ();if(v<zone.get(axis+"1").getAsDouble()||v>=zone.get(axis+"2").getAsDouble()+1)return false;}return true;}
    private String chunkKey(Block b){return b.getWorld().getUID()+":"+(b.getX()>>4)+":"+(b.getZ()>>4);}
    private void edited(Block b){ledger.section("region_edits").addProperty(chunkKey(b),System.currentTimeMillis());editsDirty=true;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void place(BlockPlaceEvent e){edited(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broken(BlockBreakEvent e){edited(e.getBlock());}
    JsonObject choose(World w) {
        int checked=0;for(var chunk:w.getLoadedChunks()) {
            if(++checked>64)break;int x=chunk.getX()*16+6,z=chunk.getZ()*16+6,y=w.getHighestBlockYAt(x,z);
            JsonObject zone=new JsonObject();zone.addProperty("world",w.getName());zone.addProperty("x1",x-2);zone.addProperty("x2",x+2);zone.addProperty("y1",y);zone.addProperty("y2",Math.min(y+3,w.getMaxHeight()-1));zone.addProperty("z1",z-2);zone.addProperty("z2",z+2);
            if(safe(zone,true))return zone;
        }
        return new JsonObject();
    }
    boolean safe(JsonObject zone,boolean idle) {
        try {
            World w=WorldInvestigation.world(zone);long volume=1;
            for(String axis:List.of("x","y","z")){long lo=WorldActions.number(zone,axis+"1",-29999984,29999984),hi=WorldActions.number(zone,axis+"2",-29999984,29999984);if(hi<lo)return false;volume*=hi-lo+1;if(volume>4096)return false;}
            if(zone.get("y1").getAsInt()<w.getMinHeight()||zone.get("y2").getAsInt()>=w.getMaxHeight())return false;
            for(int x=zone.get("x1").getAsInt()-1;x<=zone.get("x2").getAsInt()+1;x++)for(int z=zone.get("z1").getAsInt()-1;z<=zone.get("z2").getAsInt()+1;z++) {
                if(!w.isChunkLoaded(x>>4,z>>4))return false;
                String key=w.getUID()+":"+(x>>4)+":"+(z>>4);
                if(idle&&ledger.section("region_edits").has(key)&&System.currentTimeMillis()-ledger.section("region_edits").get(key).getAsLong()<600000)return false;
                for(int y=Math.max(w.getMinHeight(),zone.get("y1").getAsInt()-1);y<=Math.min(w.getMaxHeight()-1,zone.get("y2").getAsInt()+1);y++)if(!simple(w.getBlockAt(x,y,z))||!w.getWorldBorder().isInside(new Location(w,x,y,z)))return false;
            }
            for(Entity e:w.getEntities())if(inside(zone,e.getLocation()) && (WorldInvestigation.protectedEntity(e)||idle && e instanceof Player))return false;
            if(idle)for(var entry:ledger.section("activities").entrySet()){JsonObject a=entry.getValue().getAsJsonObject();if(!Set.of("OPEN","RESTORING").contains(a.get("state").getAsString())||!a.has("zone")||!a.getAsJsonObject("zone").has("world"))continue;JsonObject other=a.getAsJsonObject("zone");if(overlap(zone,other))return false;}
            return true;
        }catch(RuntimeException bad){return false;}
    }
    static boolean overlap(JsonObject a,JsonObject b){if(!a.get("world").getAsString().equals(b.get("world").getAsString()))return false;for(String axis:List.of("x","y","z"))if(a.get(axis+"2").getAsDouble()<b.get(axis+"1").getAsDouble()||b.get(axis+"2").getAsDouble()<a.get(axis+"1").getAsDouble())return false;return true;}
    void trialBoundary(JsonObject a) {
        JsonObject zone=a.getAsJsonObject("_trial_zone");if(!safe(zone,false))throw new IllegalArgumentException("试运行需要已加载安全区；容器/红石/命名或归属实体禁止");
        String type=WorldActions.string(a,"type");
        if(!Set.of("set_blocks","spawn_entity","remove_entity","relocate_entity","world_query").contains(type))throw new IllegalArgumentException("试运行禁止全世界规则/处罚/奖励/玩家状态；使用可隔离区域动作或只读查询");
        if(type.equals("world_query")){if(!zone.get("world").getAsString().equals(WorldActions.string(a,"world")))throw new IllegalArgumentException("试运行世界与活动区不一致");return;}
        if(!CapabilityGrants.matches(regionGrant(zone),a))throw new IllegalArgumentException("试运行动作超出安全区或缺少可验证区域目标");
        if(type.equals("remove_entity")||type.equals("relocate_entity"))throw new IllegalArgumentException("实体试运行缺少精确撤销依据；改用查询或方块试运行");
        if(type.equals("spawn_entity"))throw new IllegalArgumentException("生成试运行尚无可靠实体回收记录；改用查询或方块试运行");
    }
    private static JsonObject regionGrant(JsonObject zone){JsonObject g=new JsonObject();g.addProperty("world",zone.get("world").getAsString());JsonObject region=zone.deepCopy();region.remove("world");g.add("region",region);return g;}
    void validate(JsonObject a) {
        if(!Set.of("disaster_repair","ecology_rescue","ley_line").contains(WorldActions.string(a,"kind")))throw new IllegalArgumentException("活动类型须为 disaster_repair/ecology_rescue/ley_line");
        String title=WorldActions.string(a,"title");if(title.isBlank()||title.length()>80)throw new IllegalArgumentException("活动标题1..80字");WorldActions.number(a,"minutes",1,1440);
        JsonObject goal=a.getAsJsonObject("goal");if(goal==null||!Set.of("monster_count","animal_count","material_count","visits").contains(WorldActions.string(goal,"metric")))throw new IllegalArgumentException("活动目标须为真实计数 monster_count/animal_count/material_count/visits");
        if(!Set.of("ge","le").contains(WorldActions.string(goal,"compare")))throw new IllegalArgumentException("目标比较须为 ge/le");WorldActions.number(goal,"value",0,4096);
        if(goal.get("metric").getAsString().equals("material_count")){Material m=Material.matchMaterial(WorldActions.string(goal,"material"));if(m==null||!m.isBlock())throw new IllegalArgumentException("目标方块无效");}
        JsonObject reward=a.getAsJsonObject("reward");Material m=reward==null?null:Material.matchMaterial(WorldActions.string(reward,"material"));if(m==null||!m.isItem()||m.isAir()||Set.of(Material.COMMAND_BLOCK,Material.COMMAND_BLOCK_MINECART,Material.CHAIN_COMMAND_BLOCK,Material.REPEATING_COMMAND_BLOCK,Material.STRUCTURE_BLOCK,Material.JIGSAW).contains(m))throw new IllegalArgumentException("奖励须为可用原版物品");WorldActions.number(reward,"count",1,64);WorldActions.number(reward,"reputation",0,100);
    }
    String create(String id,JsonObject a) {
        validate(a);if(ledger.section("activities").has(id))return "活动已存在："+id;
        long active=ledger.section("activities").entrySet().stream().filter(e->e.getValue().getAsJsonObject().get("state").getAsString().equals("OPEN")).count();if(active>=8)throw new IllegalArgumentException("已有8个开放活动；先结束或等待完成");
        World w=WorldInvestigation.world(a);JsonObject zone=a.has("zone")?a.getAsJsonObject("zone").deepCopy():choose(w);
        if(zone.has("world")&&!safe(zone,true))throw new IllegalArgumentException("活动区不空闲或含受保护对象/近期玩家编辑，重新调查");
        JsonObject task=a.deepCopy();task.addProperty("state","OPEN");task.addProperty("created",System.currentTimeMillis());task.addProperty("until",System.currentTimeMillis()+a.get("minutes").getAsLong()*60000);task.add("zone",zone);task.add("participants",new JsonObject());task.add("recoveries",new JsonArray());task.addProperty("id",id);
        if(!zone.has("world")){JsonObject goal=new JsonObject();goal.addProperty("metric","visits");goal.addProperty("compare","ge");goal.addProperty("value",6);task.add("goal",goal);task.addProperty("fallback","没有安全空闲区，改为合作调查委托；不修改世界");task.add("meeting_point",WorldInvestigation.position(w.getSpawnLocation()));}
        else if(!a.getAsJsonObject("goal").get("metric").getAsString().equals("visits")&&goalMet(task,measure(task)))throw new IllegalArgumentException("当前世界已经满足目标；请设计确实需要完成的委托");
        ledger.section("activities").add(id,task);ledger.save();port.announce("天空岛委托 "+id+"「"+a.get("title").getAsString()+"」；/skyisland task "+id+" 查看，join 报名；服务器同人设定");return "活动已发布："+id+(zone.has("world")?"；安全区已选定":"；合作调查，不进行世界编辑");
    }
    JsonObject require(String id){JsonObject a=ledger.section("activities").getAsJsonObject(id);if(a==null)throw new IllegalArgumentException("活动不存在");return a;}
    String join(String id,Player p){JsonObject a=require(id);if(!port.authenticated(p)||!a.get("state").getAsString().equals("OPEN")||a.get("until").getAsLong()<=System.currentTimeMillis())throw new IllegalArgumentException("活动未开放或身份未验证");JsonObject participants=a.getAsJsonObject("participants");JsonObject person=participants.getAsJsonObject(p.getUniqueId().toString());if(person!=null&&Set.of("REWARDED","CLAIMING","COMPLETED").contains(person.get("state").getAsString()))throw new IllegalArgumentException("每个活动只能结算一次");if(person==null){person=new JsonObject();person.addProperty("visits",0);person.addProperty("joined",System.currentTimeMillis());}person.addProperty("state","JOINED");participants.add(p.getUniqueId().toString(),person);ledger.save();presence(p,"天空岛委托",a.get("title").getAsString());return "已报名 "+id+"；/skyisland task "+id+" 查看目标、期限与奖励；/skyisland leave "+id+" 退出";}
    String leave(String id,Player p){JsonObject a=require(id),person=a.getAsJsonObject("participants").getAsJsonObject(p.getUniqueId().toString());if(person==null)throw new IllegalArgumentException("尚未报名");if(Set.of("CLAIMING","REWARDED","COMPLETED").contains(person.get("state").getAsString()))throw new IllegalArgumentException("已结算或等待恢复，保留原记录");person.addProperty("state","LEFT");ledger.save();return "已退出活动；没有强制效果需要恢复";}
    void recovery(String id,String edit){JsonObject a=require(id);JsonArray recoveries=a.getAsJsonArray("recoveries");if(java.util.stream.StreamSupport.stream(recoveries.spliterator(),false).noneMatch(e->e.getAsString().equals(edit))){recoveries.add(edit);ledger.save();}}
    String end(String id){JsonObject a=require(id);if(a.get("state").getAsString().equals("ENDED"))return "活动已结束";a.addProperty("state","RESTORING");ledger.save();return "活动已停止报名，开始核对恢复材料："+id;}
    private int measure(JsonObject a) {
        JsonObject zone=a.getAsJsonObject("zone");String metric=a.getAsJsonObject("goal").get("metric").getAsString();if(metric.equals("visits"))return 0;
        if(!zone.has("world"))throw new IllegalArgumentException("没有可测量的活动区");World w=WorldInvestigation.world(zone);int count=0;
        if(metric.equals("material_count")){Material m=Material.matchMaterial(a.getAsJsonObject("goal").get("material").getAsString());for(int x=zone.get("x1").getAsInt();x<=zone.get("x2").getAsInt();x++)for(int z=zone.get("z1").getAsInt();z<=zone.get("z2").getAsInt();z++){if(!w.isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("活动区已卸载，不能宣布完成");for(int y=zone.get("y1").getAsInt();y<=zone.get("y2").getAsInt();y++)if(w.getBlockAt(x,y,z).getType()==m)count++;}}
        else {for(int x=zone.get("x1").getAsInt()>>4;x<=zone.get("x2").getAsInt()>>4;x++)for(int z=zone.get("z1").getAsInt()>>4;z<=zone.get("z2").getAsInt()>>4;z++)if(!w.isChunkLoaded(x,z))throw new IllegalArgumentException("活动区已卸载，等待重新测量");for(Entity e:w.getEntities())if(inside(zone,e.getLocation())&&(metric.equals("monster_count")?e instanceof org.bukkit.entity.Monster:e instanceof org.bukkit.entity.Animals))count++;}
        return count;
    }
    static boolean goalMet(JsonObject a,int value){JsonObject goal=a.getAsJsonObject("goal");return goal.get("compare").getAsString().equals("ge")?value>=goal.get("value").getAsInt():value<=goal.get("value").getAsInt();}
    void tick() {
        if(editsDirty){ledger.save();editsDirty=false;}
        if(port.paused()||System.currentTimeMillis()<nextTick)return;nextTick=System.currentTimeMillis()+10000;
        for(var entry:List.copyOf(ledger.section("activities").entrySet())) {
            JsonObject a=entry.getValue().getAsJsonObject();String id=entry.getKey();
            try {
                for(var member:a.getAsJsonObject("participants").entrySet()) {JsonObject person=member.getValue().getAsJsonObject();Player player=Bukkit.getPlayer(UUID.fromString(member.getKey()));if(player!=null&&port.authenticated(player)&&Set.of("COMPLETED","CLAIMING").contains(person.get("state").getAsString()))claim(id,a,member.getKey(),person,player);}
                if(a.get("state").getAsString().equals("OPEN")) {
                    if(a.get("until").getAsLong()<=System.currentTimeMillis()){end(id);continue;}
                    int measured=measure(a);a.addProperty("measured",measured);a.addProperty("sampled_at",System.currentTimeMillis());
                    for(var member:a.getAsJsonObject("participants").entrySet()) {
                        JsonObject person=member.getValue().getAsJsonObject();Player p=Bukkit.getPlayer(UUID.fromString(member.getKey()));if(p==null||!port.authenticated(p))continue;
                        if(person.get("state").getAsString().equals("JOINED")) {
                            boolean present=a.getAsJsonObject("zone").has("world")?inside(a.getAsJsonObject("zone"),p.getLocation()):near(a.getAsJsonObject("meeting_point"),p.getLocation());
                            if(present)person.addProperty("visits",person.get("visits").getAsInt()+1);
                            boolean met=goalMet(a,a.getAsJsonObject("goal").get("metric").getAsString().equals("visits")?person.get("visits").getAsInt():measured);
                            if(met&&person.get("visits").getAsInt()>=6){person.addProperty("state","COMPLETED");person.addProperty("completed",System.currentTimeMillis());ledger.save();p.sendMessage("§d委托 "+id+"：真实目标已验证，正在结算奖励");}
                        }
                        if(Set.of("COMPLETED","CLAIMING").contains(person.get("state").getAsString()))claim(id,a,member.getKey(),person,p);
                    }
                    ledger.save();
                }else if(a.get("state").getAsString().equals("RESTORING")) {
                    boolean complete=true;for(var value:a.getAsJsonArray("recoveries")){String edit=value.getAsString(),operation=WorldPrograms.hash("activity-undo:"+id+":"+edit).substring(0,8),state=port.state(operation);if(state.equals("MISSING")){port.restore(operation,edit);complete=false;}else if(!state.equals("DONE")){complete=false;if(state.equals("NEEDS_REVIEW"))a.addProperty("wait_reason","恢复冲突，停止覆盖玩家变化："+edit);}}
                    if(complete){a.addProperty("state","ENDED");a.addProperty("finished",System.currentTimeMillis());port.announce("天空岛委托 "+id+" 已结束；临时世界编辑已核对恢复");}ledger.save();
                }
            }catch(RuntimeException failure){a.addProperty("wait_reason",failure.getMessage());ledger.save();}
        }
    }
    private void claim(String id,JsonObject a,String uuid,JsonObject person,Player p) {
        String operation=WorldPrograms.hash("activity-reward:"+id+":"+uuid).substring(0,8);String state=port.state(operation);
        if(state.equals("MISSING")){person.addProperty("state","CLAIMING");person.addProperty("operation",operation);ledger.save();state=port.reward(operation,p,a.getAsJsonObject("reward"));}
        if(state.equals("DONE")){JsonObject profile=ledger.section("profiles").getAsJsonObject(uuid);if(profile==null)throw new IllegalStateException("奖励身份档案缺失");int reputation=profile.has("reputation")?profile.get("reputation").getAsInt():0;profile.addProperty("reputation",Math.addExact(reputation,a.getAsJsonObject("reward").get("reputation").getAsInt()));person.addProperty("state","REWARDED");ledger.save();presence(p,"地脉回应",a.get("title").getAsString()+" · 奖励已结算");}
        else if(Set.of("NEEDS_REVIEW","REJECTED","DUPLICATE").contains(state)){person.addProperty("wait_reason","奖励事务需核验："+operation+"，不重复发放");ledger.save();}
    }
    private static boolean near(JsonObject point,Location location){if(!point.get("world").getAsString().equals(location.getWorld().getName()))return false;double x=point.get("x").getAsDouble()-location.getX(),y=point.get("y").getAsDouble()-location.getY(),z=point.get("z").getAsDouble()-location.getZ();return x*x+y*y+z*z<=64;}
    static void presence(Player p,String title,String text){p.sendTitle("§5"+title,"§d"+text,10,50,15);p.playSound(p.getLocation(),org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME,.6f,1.1f);p.spawnParticle(org.bukkit.Particle.END_ROD,p.getLocation().add(0,1,0),12,.5,.7,.5,.01);}
    JsonObject publicTask(String id){JsonObject original=require(id),a=new JsonObject();for(String key:List.of("id","kind","title","goal","reward","world","zone","meeting_point","state","created","until","measured","sampled_at","fallback","wait_reason"))if(original.has(key))a.add(key,original.get(key).deepCopy());return a;}
    String playerTask(String id,Player player){JsonObject a=require(id),goal=a.getAsJsonObject("goal"),reward=a.getAsJsonObject("reward"),zone=a.getAsJsonObject("zone");String metric=switch(goal.get("metric").getAsString()){case "monster_count"->"怪物数量";case "animal_count"->"动物数量";case "material_count"->goal.get("material").getAsString()+" 方块数量";default->"在场采样次数（每10秒一次）";};String region=zone.has("world")?zone.get("world").getAsString()+"：("+zone.get("x1")+","+zone.get("y1")+","+zone.get("z1")+") 至 ("+zone.get("x2")+","+zone.get("y2")+","+zone.get("z2")+")":"合作调查集合点："+a.get("meeting_point");JsonObject person=player==null?null:a.getAsJsonObject("participants").getAsJsonObject(player.getUniqueId().toString());return "§d天空岛委托 "+id+"「"+a.get("title").getAsString()+"」\n§7状态："+displayState(a.get("state").getAsString())+"；截止："+java.time.Instant.ofEpochMilli(a.get("until").getAsLong())+"\n§f目标："+metric+(goal.get("compare").getAsString().equals("ge")?" ≥ ":" ≤ ")+goal.get("value")+"；所有委托需至少6次在场采样\n§7地点："+region+"\n§6奖励："+reward.get("material").getAsString()+" × "+reward.get("count")+"；声望 +"+reward.get("reputation")+"（每人一次）\n§b你的进度："+(person==null?"未报名":displayState(person.get("state").getAsString())+"；在场="+person.get("visits"))+"\n§e/skyisland join "+id+" 报名；leave "+id+" 退出\n§8服务器同人设定；依据实际世界测量判定，不以角色发言代替完成。";}
    static String displayState(String state){return switch(state){case "OPEN"->"报名开放";case "RESTORING"->"恢复核验中";case "ENDED"->"已结束";case "JOINED"->"参与中";case "LEFT"->"已退出";case "COMPLETED","CLAIMING"->"目标已达成，奖励核验中";case "REWARDED"->"奖励已领取";default->"等待复核";};}
    List<JsonObject> list(){List<JsonObject> rows=new ArrayList<>();for(String id:ledger.section("activities").keySet())rows.add(publicTask(id));return rows;}
}
