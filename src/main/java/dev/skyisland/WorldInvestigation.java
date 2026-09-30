package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

final class WorldInvestigation {
    static final Set<String> PAGED=Set.of("entities","players","entity_hotspots","capability_catalog","programs","activities","experience","memory","execution_history","region_summary","case_evidence","dimension_status","death_evidence","portal_risks","snapshot_preview");
    static final Set<String> TYPES=java.util.stream.Stream.concat(PAGED.stream(),Set.of("player_state","nearby_entities","time_trend","backup_status","laws","safe_activity_region").stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    static JsonObject page(List<JsonObject> rows,JsonObject query) {
        int offset=query.has("offset")?(int)WorldActions.number(query,"offset",0,1000000):0;
        if(offset>rows.size())throw new IllegalArgumentException("offset 超出当前采样范围，请从0重查");
        int end=Math.min(offset+20,rows.size());JsonArray items=new JsonArray();rows.subList(offset,end).forEach(items::add);
        JsonObject out=new JsonObject(),data=new JsonObject();data.add("items",items);out.add("data",data);out.addProperty("offset",offset);out.addProperty("total",rows.size());out.addProperty("next",end<rows.size()?end:-1);out.addProperty("complete",end==rows.size());return out;
    }
    static JsonObject wrap(JsonObject data) {JsonObject out=new JsonObject();out.add("data",data);out.addProperty("offset",0);out.addProperty("total",1);out.addProperty("next",-1);out.addProperty("complete",true);return out;}
    static JsonObject evidence(JsonObject out,JsonObject query) {
        out.addProperty("sampled_at",System.currentTimeMillis());out.addProperty("evidence_id",WorldPrograms.hash(query+":"+out.get("data")).substring(0,16));out.add("query",query.deepCopy());out.addProperty("status","OK");return out;
    }
    static JsonObject entity(Entity e) {
        JsonObject row=position(e.getLocation());row.addProperty("uuid",e.getUniqueId().toString());row.addProperty("entity",e.getType().name());row.addProperty("target_type",target(e));
        row.addProperty("named",e.getCustomName()!=null);row.addProperty("protected",protectedEntity(e));row.addProperty("age_ticks",e.getTicksLived());return row;
    }
    static String target(Entity e){return e instanceof org.bukkit.entity.Monster?"MONSTER":e instanceof org.bukkit.entity.Animals?"ANIMAL":e instanceof org.bukkit.entity.Item?"ITEM":e instanceof Player?"PLAYER":"OTHER";}
    static boolean protectedEntity(Entity e) {
        return e instanceof Player || e.getCustomName()!=null || !e.getScoreboardTags().isEmpty() || !e.getPersistentDataContainer().isEmpty()
            || !e.getPassengers().isEmpty() || e.getVehicle()!=null || e instanceof org.bukkit.entity.Tameable t && t.isTamed()
            || e instanceof org.bukkit.entity.LivingEntity l && (l.isLeashed() || l instanceof org.bukkit.entity.Monster && !l.getRemoveWhenFarAway())
            || e instanceof org.bukkit.entity.Item i && (i.getOwner()!=null||i.getThrower()!=null||i.getItemStack().hasItemMeta());
    }
    static JsonObject position(Location p){JsonObject out=new JsonObject();out.addProperty("world",p.getWorld().getName());out.addProperty("x",p.getX());out.addProperty("y",p.getY());out.addProperty("z",p.getZ());return out;}
    static JsonObject entities(JsonObject q) {
        World w=world(q);int cx=q.has("chunk_x")?(int)WorldActions.number(q,"chunk_x",-1874999,1874999):Integer.MIN_VALUE;
        int cz=q.has("chunk_z")?(int)WorldActions.number(q,"chunk_z",-1874999,1874999):Integer.MIN_VALUE;
        List<JsonObject> rows=new ArrayList<>();String kind=AgentReply.string(q,"target_type","");
        if(cx!=Integer.MIN_VALUE || cz!=Integer.MIN_VALUE) {
            if(cx==Integer.MIN_VALUE||cz==Integer.MIN_VALUE||!w.isChunkLoaded(cx,cz))throw new IllegalArgumentException("同时提供已加载 chunk_x/chunk_z");
            for(Entity e:w.getChunkAt(cx,cz).getEntities())if(kind.isBlank()||kind.equals(target(e)))rows.add(entity(e));
        }else for(Entity e:w.getEntities())if(kind.isBlank()||kind.equals(target(e)))rows.add(entity(e));
        rows.sort(Comparator.comparing(r->r.get("uuid").getAsString()));return page(rows,q);
    }
    static World world(JsonObject q){World w=Bukkit.getWorld(WorldActions.string(q,"world"));if(w==null)throw new IllegalArgumentException("world 须为已加载世界名；查询 dimension_status");return w;}
    static JsonObject region(JsonObject q) {
        World w=world(q);int x=(int)WorldActions.number(q,"x",-29999984,29999984),z=(int)WorldActions.number(q,"z",-29999984,29999984),y=(int)WorldActions.number(q,"y",w.getMinHeight(),w.getMaxHeight()-1);
        int size=q.has("size")?(int)WorldActions.number(q,"size",1,16):8;if(y+size>w.getMaxHeight())throw new IllegalArgumentException("区域超出世界高度");
        TreeMap<String,Integer> counts=new TreeMap<>();boolean protectedBlocks=false;
        for(int bx=x;bx<x+size;bx++)for(int bz=z;bz<z+size;bz++)for(int by=y;by<y+size;by++) {
            if(!w.isChunkLoaded(bx>>4,bz>>4))throw new IllegalArgumentException("只调查已加载区块，不会自动加载世界");
            org.bukkit.block.Block b=w.getBlockAt(bx,by,bz);counts.merge(b.getType().name(),1,Integer::sum);if(!WorldActivities.simple(b))protectedBlocks=true;
        }
        List<JsonObject> rows=new ArrayList<>();counts.forEach((m,c)->{JsonObject row=new JsonObject();row.addProperty("material",m);row.addProperty("count",c);rows.add(row);});
        JsonObject out=page(rows,q);out.getAsJsonObject("data").addProperty("contains_protected_blocks",protectedBlocks);out.getAsJsonObject("data").addProperty("size",size);return out;
    }
    static List<JsonObject> rows(JsonArray array){List<JsonObject> out=new ArrayList<>();for(JsonElement e:array)out.add(e.isJsonObject()?e.getAsJsonObject():text(e.toString()));return out;}
    static JsonObject text(String value){JsonObject out=new JsonObject();out.addProperty("text",value);return out;}
}
