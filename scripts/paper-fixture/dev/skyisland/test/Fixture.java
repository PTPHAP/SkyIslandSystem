package dev.skyisland.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only setup and assertions; never installed in the user's server or release ZIP. */
public final class Fixture extends JavaPlugin {
    private Plugin sky;private Object phanes,ronova,istaroth;private JsonObject results=new JsonObject();
    @Override public void onEnable(){sky=Bukkit.getPluginManager().getPlugin("SkyIslandSystem");try{if(Files.exists(getDataFolder().toPath().resolve("results.json")))results=JsonParser.parseString(Files.readString(getDataFolder().toPath().resolve("results.json"),StandardCharsets.UTF_8)).getAsJsonObject();Class<?> role=sky.getClass().getClassLoader().loadClass("dev.skyisland.AgentRole");phanes=Enum.valueOf((Class)role,"PHANES");ronova=Enum.valueOf((Class)role,"RONOVA");istaroth=Enum.valueOf((Class)role,"ISTAROTH");}catch(Exception e){throw new IllegalStateException(e);}}
    private Object get(Object target,String field)throws Exception{var f=target.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(target);}
    private Object call(Object target,String name,Object...args)throws Exception{for(Method m:target.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);try{return m.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException(name+":"+e.getCause().getMessage(),e.getCause());}}throw new NoSuchMethodException(name);}
    private JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private String dispatch(Object role,String id,String action)throws Exception{return (String)call(sky,"programDispatch",role,id,json(action),false);}
    private JsonObject state()throws Exception{return JsonParser.parseString(Files.readString(sky.getDataFolder().toPath().resolve("governance/index.json"),StandardCharsets.UTF_8)).getAsJsonObject();}
    private void check(String key,boolean ok)throws Exception{results.addProperty(key,ok);Files.createDirectories(getDataFolder().toPath());Files.writeString(getDataFolder().toPath().resolve("results.json"),results.toString(),StandardCharsets.UTF_8);if(!ok)throw new IllegalStateException("ASSERT "+key);getLogger().info("PASS "+key);}
    private JsonObject zone(){return json("{world:'world',x1:1000,y1:80,z1:1000,x2:1004,y2:83,z2:1004}");}
    private void bootstrap()throws Exception {
        World w=Bukkit.getWorld("world");for(int cx=61;cx<=63;cx++)for(int cz=61;cz<=63;cz++){w.getChunkAt(cx,cz).load();w.setChunkForceLoaded(cx,cz,true);}
        for(int x=995;x<=1010;x++)for(int z=995;z<=1010;z++){w.getBlockAt(x,79,z).setType(Material.STONE);w.getBlockAt(x,80,z).setType(Material.STONE);for(int y=81;y<=85;y++)w.getBlockAt(x,y,z).setType(Material.AIR);}
        w.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING,false);check("initialized",get(sky,"governanceFailure")==null);
        Object ledger=get(sky,"ledger");String privateCase=(String)call(ledger,"open",null,"investigation",null,"fixture privacy");
        call(ledger,"share",privateCase,ronova);call(ledger,"note",ronova,json("{text:'PRIVATE_RONOVA_FIXTURE'}"));
        check("private_own_query",call(sky,"investigate",json("{type:'memory'}"),ronova).toString().contains("PRIVATE_RONOVA_FIXTURE"));
        check("private_other_query",!call(sky,"investigate",json("{type:'memory'}"),istaroth).toString().contains("PRIVATE_RONOVA_FIXTURE"));
        Class<?> replyClass=sky.getClass().getClassLoader().loadClass("dev.skyisland.AgentReply");Method parse=replyClass.getDeclaredMethod("parse",String.class);parse.setAccessible(true);
        Object reply=parse.invoke(null,"{\"message\":\"fixture private investigation\",\"query\":{\"type\":\"memory\"}}");
        call(sky,"handleDecision",ronova,"fixture privacy",null,"案件 "+privateCase,null,"",0,reply);
        check("private_case_no_leak",!call(ledger,"caseSummary",privateCase,true).toString().contains("PRIVATE_RONOVA_FIXTURE"));
        check("fine_allowed",((String)call(sky,"authority",istaroth,json("{type:'set_gamerule',world:'world',rule:'doDaylightCycle',value:false}"))).isEmpty());
        check("fine_denied",!((String)call(sky,"authority",istaroth,json("{type:'set_gamerule',world:'world',rule:'doMobSpawning',value:false}"))).isEmpty());
        Object capabilities=get(sky,"capabilities");call(capabilities,"grant",json("{role:'ronova',capability:'time.set',world:'world',minutes:1}"),"070f0001",true);
        check("cross_duty",dispatch(ronova,"070f0002","{type:'set_time',world:'world',ticks:1000}").equals("DONE"));
        call(capabilities,"revoke","070f0001");check("revoke_denied",dispatch(ronova,"070f0003","{type:'set_time',world:'world',ticks:2000}").equals("REJECTED"));
        check("external_rejected",dispatch(phanes,"070f0004","{type:'op',player:'Anyone'}").equals("REJECTED"));
        Object programs=get(sky,"programs");JsonObject draft=json("{program:'repair',_operation_id:'070f0005',body:{steps:[{op:'action',action:{type:'set_blocks',world:'world',x1:1001,y1:81,z1:1001,x2:1001,y2:81,z2:1001,material:'STONE'}},{op:'query',query:{type:'region_summary',world:'world',x:1001,y:81,z:1001,size:1},save:'region'}],assertions:[{left:'$region.data.items',compare:'contains',right:{material:'STONE',count:1}}]}}");
        call(programs,"draft",phanes,draft);call(programs,"validate","repair",1);JsonObject trial=json("{type:'trial_program',program:'repair',version:1}");trial.add("zone",zone());call(sky,"programDispatch",phanes,"070f0006",trial,false);
        check("trial_started",state().getAsJsonObject("program_runs").has("070f0006"));
        Object activities=get(sky,"activities");check("safe_zone",(boolean)call(activities,"safe",zone(),true));
        Bukkit.getScheduler().runTaskLater(this,()->{try{JsonObject s=state();check("trial_actual_and_restored",s.getAsJsonObject("program_runs").getAsJsonObject("070f0006").get("state").getAsString().equals("DONE")&&w.getBlockAt(1001,81,1001).getType()==Material.AIR);call(programs,"publish","repair",1);check("published",state().getAsJsonObject("programs").getAsJsonObject("repair").get("active").getAsInt()==1);}catch(Exception e){getLogger().severe("FAIL "+e.getMessage());}},120L);
    }
    private void activity(Player p)throws Exception {
        p.getInventory().clear();p.saveData();
        Object ledger=get(sky,"ledger"),grants=get(sky,"capabilities");String caseId=(String)call(ledger,"open",null,"place",p.getUniqueId(),"fixture permission evidence");
        call(grants,"grant",json("{role:'ronova',capability:'player.penalty.warn',world:'world',target_type:'PLAYER'}"),"070f0011",false);
        JsonObject penalty=json("{type:'punish_player',kind:'warn',law:'place',reason:'fixture only',world:'world_nether'}");penalty.addProperty("case_id",caseId);
        check("penalty_actual_target",!call(sky,"authority",ronova,penalty).toString().isBlank());call(grants,"revoke","070f0011");
        boolean exempt=false;p.setOp(true);try{call(get(sky,"playerGovernance"),"validate",penalty);}catch(Exception expected){exempt=expected.getMessage().contains("管理员");}finally{p.setOp(false);((JsonObject)call(ledger,"section","profiles")).getAsJsonObject(p.getUniqueId().toString()).addProperty("admin",false);call(ledger,"save");}
        check("admin_penalty_exempt",exempt);
        JsonObject localGrant=json("{role:'ronova',capability:'item.reward',world:'world'}");localGrant.add("region",zone());call(grants,"grant",localGrant,"070f0012",true);
        JsonObject fakePosition=json("{type:'give_item',material:'DIAMOND',count:1,x1:1001,y1:81,z1:1001,x2:1001,y2:81,z2:1001}");fakePosition.addProperty("player",p.getName());
        check("point_scope_not_spoofed",!call(sky,"authority",ronova,fakePosition).toString().isBlank());call(grants,"revoke","070f0012");
        JsonObject a=json("{kind:'ley_line',title:'fixture cooperation',world:'world',minutes:3,goal:{metric:'visits',compare:'ge',value:6},reward:{material:'DIAMOND',count:2,reputation:3}}");
        a.add("zone",zone());Object activities=get(sky,"activities");call(activities,"create","070f0010",a);call(activities,"join","070f0010",p);p.teleport(new Location(Bukkit.getWorld("world"),1002.5,81,1002.5));
        JsonObject publicTask=(JsonObject)call(activities,"publicTask","070f0010");check("public_task_filtered",!publicTask.has("recoveries")&&!publicTask.has("participants")&&!publicTask.has("case_id"));
        Bukkit.getScheduler().runTaskLater(this,()->{try {int count=p.getInventory().all(Material.DIAMOND).values().stream().mapToInt(i->i.getAmount()).sum();check("reward_once",count==2);JsonObject profile=state().getAsJsonObject("profiles").getAsJsonObject(p.getUniqueId().toString());check("reputation_once",profile.get("reputation").getAsInt()==3);call(activities,"end","070f0010");call(activities,"tick");check("completed_record",state().getAsJsonObject("activities").getAsJsonObject("070f0010").getAsJsonObject("participants").getAsJsonObject(p.getUniqueId().toString()).get("state").getAsString().equals("REWARDED"));}catch(Exception e){getLogger().severe("FAIL "+e.getMessage());}},20L*70);
    }
    private void conflicts()throws Exception {
        World w=Bukkit.getWorld("world");Object programs=get(sky,"programs");JsonObject run=json("{type:'run_program',program:'repair',version:1}");call(sky,"programDispatch",phanes,"070f0020",run,false);
        Bukkit.getScheduler().runTaskLater(this,()->{try{check("published_run",w.getBlockAt(1001,81,1001).getType()==Material.STONE);w.getBlockAt(1001,81,1001).setType(Material.GLASS);JsonObject saved=state().getAsJsonObject("program_runs").getAsJsonObject("070f0020");String edit=saved.getAsJsonArray("receipts").get(0).getAsJsonObject().getAsJsonObject("action").get("_operation_id").getAsString();dispatch(phanes,"070f0021","{type:'undo_blocks',edit_id:'"+edit+"'}");Bukkit.getScheduler().runTaskLater(this,()->{try{check("concurrent_change_preserved",w.getBlockAt(1001,81,1001).getType()==Material.GLASS);check("restore_conflict_recorded",state().getAsJsonObject("operations").getAsJsonObject("070f0021").get("state").getAsString().equals("NEEDS_REVIEW"));}catch(Exception e){getLogger().severe("FAIL "+e.getMessage());}},60L);}catch(Exception e){getLogger().severe("FAIL "+e.getMessage());}},100L);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){try{if(!(sender instanceof org.bukkit.command.ConsoleCommandSender))throw new IllegalArgumentException("fixture console only");switch(args[0]){case "bootstrap"->bootstrap();case "activity"->activity(Bukkit.getPlayerExact(args[1]));case "conflicts"->conflicts();case "paused"->{dispatch(phanes,"070f0030","{type:'run_program',program:'repair',version:1}");call(sky,"setPaused",true);check("pause_preserved",state().getAsJsonObject("program_runs").getAsJsonObject("070f0030").get("state").getAsString().equals("RUNNING"));}case "resumed"->{Bukkit.getWorld("world").getChunkAt(62,62).load();call(sky,"setPaused",false);Bukkit.getScheduler().runTaskLater(this,()->{try{check("restart_cursor_resumed",state().getAsJsonObject("program_runs").getAsJsonObject("070f0030").get("state").getAsString().equals("DONE"));}catch(Exception e){getLogger().severe("FAIL "+e.getMessage());}},120L);}default->throw new IllegalArgumentException("unknown fixture");}}catch(Exception e){getLogger().severe("FAIL "+e.getMessage());}return true;}
}
