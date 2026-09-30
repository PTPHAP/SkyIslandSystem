package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The executable vocabulary. Translated commands and program steps resolve here too. */
final class CapabilityCatalog {
    record Entry(String id, String action, String field, String value, Set<AgentRole> defaults, String fields) {}
    static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    static {
        add("time.set","set_time","world,ticks=0..23999",AgentRole.ISTAROTH);
        add("time.add","add_time","world,ticks=0..23999",AgentRole.ISTAROTH);
        add("time.observe","world_query","world",AgentRole.values());
        add("weather.set","set_weather","world,storm:boolean,thunder?:boolean",AgentRole.ASMODAY);
        for (String rule : List.of("doDaylightCycle","doWeatherCycle","doMobSpawning","randomTickSpeed","keepInventory","doEntityDrops","doMobLoot"))
            variant("rule."+rule,"set_gamerule","rule",rule,"world,rule,value (randomTickSpeed=0..20, others boolean)",
                rule.equals("doDaylightCycle")?AgentRole.ISTAROTH:rule.equals("doWeatherCycle")?AgentRole.ASMODAY:
                Set.of("keepInventory","doEntityDrops","doMobLoot").contains(rule)?AgentRole.RONOVA:AgentRole.NABERIUS);
        add("border.set","set_border","world,size=32..60000000",AgentRole.ASMODAY);
        add("player.teleport","teleport","player,world,x,y,z (safe loaded destination)",AgentRole.ASMODAY);
        for(String entity:List.of("ZOMBIE","SKELETON","SHEEP","COW","PIG","CHICKEN","VILLAGER"))
            variant("entity.spawn."+entity.toLowerCase(),"spawn_entity","entity",entity,"world,x,y,z,entity,count=1..5",AgentRole.NABERIUS);
        variant("entity.remove.monster","remove_entity","_target_type","MONSTER","uuid; world/target determined by server",AgentRole.RONOVA);
        variant("entity.remove.item","remove_entity","_target_type","ITEM","uuid; world/target determined by server",AgentRole.RONOVA);
        for(String kind:List.of("MONSTER","OLD_ITEM"))variant("entity.relieve."+kind.toLowerCase(),"relieve_entity_pressure","_target_type",kind,"incident_id,emergency?:boolean",kind.equals("MONSTER")?AgentRole.NABERIUS:AgentRole.RONOVA);
        for(String kind:List.of("ANIMAL","MONSTER"))variant("entity.move."+kind.toLowerCase(),"relocate_entity","_target_type",kind,"uuid,world,x,y,z; protected entities excluded",AgentRole.NABERIUS,AgentRole.ASMODAY);
        add("region.edit","set_blocks","world,x1,y1,z1,x2,y2,z2,material; snapshot<=4096,128/tick",AgentRole.ASMODAY);
        add("snapshot.restore","undo_blocks","edit_id; server resolves original world and region",AgentRole.ISTAROTH);
        for(String kind:List.of("warn","restrict","kick","tempban","permanentban"))variant("player.penalty."+kind,"punish_player","kind",kind,"case_id,law,kind,reason; restrict/tempban minutes=1..43200; restrict capability",AgentRole.values());
        add("player.penalty.revoke","pardon_player","sanction_id");
        add("item.reward","give_item","player,material,count=1..2304",AgentRole.NABERIUS);
        add("item.hold","confiscate_item","case_id,law,player,material,count=1..2304",AgentRole.RONOVA);
        add("item.return","restore_items","escrow_id",AgentRole.RONOVA);
        for(String effect:List.of("speed","haste","strength","instant_health","jump_boost","regeneration","resistance","fire_resistance","water_breathing","invisibility","night_vision","health_boost","absorption","saturation","luck","slow_falling","conduit_power","dolphins_grace","hero_of_the_village"))
            variant("player.effect."+effect,"set_effect","effect",effect,"player,effect,seconds=1..3600,amplifier=0..4",AgentRole.NABERIUS);
        for(String effect:List.of("slowness","mining_fatigue","instant_damage","nausea","blindness","hunger","weakness","poison","wither","levitation","unluck","bad_omen","darkness","glowing"))
            variant("player.effect."+effect,"set_effect","effect",effect,"player,effect,seconds=1..3600,amplifier=0..4; negative effects require Phanes grant");
        for(String mode:List.of("SURVIVAL","ADVENTURE","SPECTATOR","CREATIVE"))variant("player.mode."+mode.toLowerCase(),"set_player_mode","mode",mode,"player,mode; administrators exempt",AgentRole.ASMODAY);
        for(String signal:List.of("place","break","tnt","spawn-egg","command"))variant("law."+signal,"set_law","signal",signal,"signal,limit,window_seconds,ban_minutes,reason,emergency; runtime laws query gives bounds",signal.equals("tnt")?AgentRole.RONOVA:signal.equals("spawn-egg")?AgentRole.NABERIUS:signal.equals("command")?AgentRole.ISTAROTH:AgentRole.ASMODAY);
        for(String type:List.of("declare_plan","schedule_season","set_shadow_scope","pardon_shadow","close_case","grant_capability","revoke_capability","publish_program","disable_program","switch_program","create_activity","end_activity"))add("governance."+type,type,"see tool manual");
        for(String type:List.of("memory_note","report_defect","draft_program","validate_program","trial_program","run_program"))add("tool."+type,type,"see tool manual",AgentRole.values());
    }
    private static void add(String id,String type,String fields,AgentRole... roles){variant(id,type,"","",fields,roles);}
    private static void variant(String id,String type,String field,String value,String fields,AgentRole... roles){ENTRIES.put(id,new Entry(id,type,field,value,Set.of(roles),fields));}
    static Entry resolve(JsonObject action) {
        String type=WorldActions.string(action,"type");
        return ENTRIES.values().stream().filter(e->e.action.equals(type) && (e.field.isEmpty() || e.value.equalsIgnoreCase(AgentReply.string(action,e.field,""))))
            .findFirst().orElseThrow(()->new IllegalArgumentException("没有此可执行操作/目标类型；查询 capability_catalog 获取准确字段"));
    }
    static List<String> forAction(String type){return ENTRIES.values().stream().filter(e->e.action.equals(type)).map(Entry::id).toList();}
    static boolean known(String type){return type.equals("minecraft_command") || !forAction(type).isEmpty();}
    static JsonArray describe(AgentRole role) {
        JsonArray out=new JsonArray();
        for(Entry e:ENTRIES.values()) {JsonObject row=new JsonObject();row.addProperty("capability",e.id);row.addProperty("action",e.action);row.addProperty("selector_field",e.field);row.addProperty("selector_value",e.value);row.addProperty("fields",e.fields);row.addProperty("observe",true);row.addProperty("propose",true);row.addProperty("approve",role==AgentRole.PHANES);row.addProperty("default",role==AgentRole.PHANES || e.defaults.contains(role));out.add(row);}
        return out;
    }
}
