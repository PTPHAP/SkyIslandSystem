package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Explicit grants override defaults; a scoped allow never silently broadens another scope. */
final class CapabilityGrants {
    private final Path file;
    private final JsonObject state;
    CapabilityGrants(Path folder) {
        file=folder.resolve("capabilities.json");state=JsonState.read(file);
        if(!state.has("version")){state.addProperty("version",1);state.add("grants",new JsonObject());
            Path old=folder.resolve("shadow-discipline.properties");
            if(Files.exists(old))try{
                Files.copy(old,folder.resolve("shadow-discipline.pre-v070-"+System.currentTimeMillis()+".properties"));
                java.util.Properties legacy=new java.util.Properties();try(var in=Files.newInputStream(old)){legacy.load(in);}
                java.util.Map<AgentRole,java.util.Set<String>> original=java.util.Map.of(
                    AgentRole.RONOVA,java.util.Set.of("remove_entity","relieve_entity_pressure","minecraft_command","set_law","punish_player","confiscate_item","restore_items"),
                    AgentRole.NABERIUS,java.util.Set.of("spawn_entity","set_gamerule","relieve_entity_pressure","minecraft_command","set_law","punish_player","give_item","set_effect"),
                    AgentRole.ISTAROTH,java.util.Set.of("set_time","set_gamerule","minecraft_command","set_law","punish_player","add_time","world_query","undo_blocks"),
                    AgentRole.ASMODAY,java.util.Set.of("set_border","teleport","minecraft_command","set_law","punish_player","set_blocks","set_player_mode"));
                for(var role:original.keySet()) {
                    String scope=legacy.getProperty(role.id+".scope");if(scope==null)continue;
                    java.util.Set<String> allowed=scope.isBlank()?java.util.Set.of():new java.util.HashSet<>(java.util.Arrays.asList(scope.split(",")));
                    for(var e:CapabilityCatalog.ENTRIES.values()) {
                        if(e.action().equals("world_query"))continue;
                        boolean oldDefault=original.get(role).contains(e.action()),custom=allowed.contains(e.action());if(oldDefault==custom)continue;
                        JsonObject grant=new JsonObject();grant.addProperty("role",role.id);grant.addProperty("capability",e.id());grant.addProperty("allowed",custom);grant.addProperty("active",true);grant.addProperty("expires",0);grant.addProperty("source","v0.6 custom scope migration");
                        state.getAsJsonObject("grants").add("migrate-"+role.id+"-"+e.id(),grant);
                    }
                }
            }
            catch(java.io.IOException failed){throw new IllegalStateException("旧权能备份失败",failed);}
            JsonState.write(file,state);
        }
        if(state.get("version").getAsInt()!=1 || !state.get("grants").isJsonObject())throw new IllegalStateException("细分授权档案版本错误");
    }
    String check(AgentRole role,JsonObject a) {
        CapabilityCatalog.Entry e=CapabilityCatalog.resolve(a);
        if(role==AgentRole.PHANES)return "";
        boolean allowed=e.defaults().contains(role);long now=System.currentTimeMillis();
        for(var item:state.getAsJsonObject("grants").entrySet()) {
            JsonObject g=item.getValue().getAsJsonObject();
            if(!g.get("active").getAsBoolean() || !g.get("role").getAsString().equals(role.id) || !g.get("capability").getAsString().equals(e.id())
                || g.get("expires").getAsLong()!=0 && g.get("expires").getAsLong()<=now || !matches(g,a))continue;
            allowed=g.get("allowed").getAsBoolean();
        }
        return allowed?"":"尚未授权 "+e.id()+"；向法涅斯申请 grant_capability（可限定 world/region/case_id/target_type/minutes）";
    }
    static boolean matches(JsonObject g,JsonObject a) {
        for(String key:java.util.List.of("world","case_id"))if(g.has(key) && !g.get(key).getAsString().equals(AgentReply.string(a,key,"")))return false;
        if(g.has("target_type") && !g.get("target_type").getAsString().equals(AgentReply.string(a,"_target_type",AgentReply.string(a,"entity",AgentReply.string(a,"kind","")))))return false;
        if(g.has("region")) {
            JsonObject r=g.getAsJsonObject("region");
            for(String axis:java.util.List.of("x","y","z")) {
                String first=a.has(axis+"1")?axis+"1":axis,last=a.has(axis+"2")?axis+"2":axis;
                if(!a.has(first)||!a.has(last))return false;
                double low=Math.min(a.get(first).getAsDouble(),a.get(last).getAsDouble()),high=Math.max(a.get(first).getAsDouble(),a.get(last).getAsDouble());
                if(!Double.isFinite(low)||!Double.isFinite(high)||low<r.get(axis+"1").getAsDouble()||high>r.get(axis+"2").getAsDouble())return false;
            }
        }
        return true;
    }
    void validate(JsonObject a) {
        if(AgentRole.parse(WorldActions.string(a,"role"))==AgentRole.PHANES)throw new IllegalArgumentException("法涅斯权能不能通过四影授权调整");
        if(!CapabilityCatalog.ENTRIES.containsKey(WorldActions.string(a,"capability")))throw new IllegalArgumentException("能力编号不存在");
        if(a.has("minutes"))WorldActions.number(a,"minutes",1,525600);
        if(a.has("allowed")&&(!a.get("allowed").isJsonPrimitive()||!a.get("allowed").getAsJsonPrimitive().isBoolean()))throw new IllegalArgumentException("allowed 必须是布尔值");
        if(a.has("world") && WorldActions.string(a,"world").isBlank())throw new IllegalArgumentException("world 不能为空");
        if(a.has("region")) {
            if(!a.has("world"))throw new IllegalArgumentException("区域授权须带 world");
            JsonObject r=a.getAsJsonObject("region");
            for(String axis:java.util.List.of("x","y","z")){double lo=WorldActions.number(r,axis+"1",-29999984,29999984),hi=WorldActions.number(r,axis+"2",-29999984,29999984);if(lo>hi)throw new IllegalArgumentException("区域最小值大于最大值");}
        }
    }
    String grant(JsonObject a,String operation,boolean allowed) {
        validate(a);JsonObject g=a.deepCopy();g.remove("type");g.remove("_operation_id");
        g.addProperty("allowed",allowed);g.addProperty("active",true);g.addProperty("expires",a.has("minutes")?System.currentTimeMillis()+a.get("minutes").getAsLong()*60000:0);
        String id=operation.isBlank()?UUID.randomUUID().toString().substring(0,8):operation;
        if(state.getAsJsonObject("grants").has(id))return "此授权操作已保存："+id;
        state.getAsJsonObject("grants").add(id,g);JsonState.write(file,state);return (allowed?"已授予":"已收回")+g.get("role").getAsString()+" "+g.get("capability").getAsString()+"；授权="+id;
    }
    String revoke(String id) {JsonObject g=state.getAsJsonObject("grants").getAsJsonObject(id);if(g==null)throw new IllegalArgumentException("授权编号不存在");g.addProperty("active",false);JsonState.write(file,state);return "授权已撤销："+id;}
    JsonArray describe(AgentRole role) {JsonArray list=CapabilityCatalog.describe(role);for(var value:list){JsonObject e=value.getAsJsonObject();JsonArray grants=new JsonArray();state.getAsJsonObject("grants").entrySet().stream().filter(x->x.getValue().getAsJsonObject().get("capability").getAsString().equals(e.get("capability").getAsString()) && (role==AgentRole.PHANES||x.getValue().getAsJsonObject().get("role").getAsString().equals(role.id))).forEach(x->{JsonObject g=x.getValue().getAsJsonObject().deepCopy();g.addProperty("id",x.getKey());grants.add(g);});e.add("grants",grants);}return list;}
}
