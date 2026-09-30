package dev.skyisland;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldProgramsTest {
    @TempDir Path folder;
    final JsonObject readBody=V070GovernanceTest.json("{steps:[{op:'query',query:{type:'time_trend'},save:'tps'},{op:'if',condition:{left:'$tps.data.tps',compare:'gt',right:15},then:[{op:'return',value:'healthy'}],else:[{op:'return',value:'slow'}]}],assertions:[{left:'$tps.data.tps',compare:'gt',right:0}]}");
    class Fake implements WorldPrograms.Port {
        GovernanceLedger ledger;boolean paused;int dispatches;boolean reject;List<String> outcomes=new ArrayList<>();
        Fake(GovernanceLedger ledger){this.ledger=ledger;}
        public JsonObject query(AgentRole r,JsonObject q){if(q.get("type").getAsString().equals("entities")){int offset=q.has("offset")?q.get("offset").getAsInt():0;List<JsonObject> rows=new ArrayList<>();for(int i=0;i<25;i++)rows.add(V070GovernanceTest.json("{index:"+i+"}"));return WorldInvestigation.page(rows,q);}return V070GovernanceTest.json("{data:{tps:20},next:-1}");}
        public String dispatch(AgentRole r,String id,JsonObject a,boolean trial){if(ledger.section("operations").has(id))return operationState(id);dispatches++;ledger.operation(id,a,r,reject?"REJECTED":"DONE",reject?"failure":"actual success");return operationState(id);}
        public String operationState(String id){return ledger.section("operations").has(id)?ledger.section("operations").getAsJsonObject(id).get("state").getAsString():"MISSING";}
        public JsonObject receipt(String id){return ledger.section("operations").getAsJsonObject(id).deepCopy();}
        public void preview(AgentRole r,JsonObject a,boolean trial){}
        public String restore(AgentRole r,String id,String edit){return dispatch(AgentRole.PHANES,id,V070GovernanceTest.json("{type:'undo_blocks',edit_id:'"+edit+"'}"),false);}
        public boolean paused(){return paused;}
        public void finished(String id,AgentRole r,String state,String result){outcomes.add(state);}
    }
    private WorldPrograms setup(GovernanceLedger ledger,Fake port,String id,JsonObject body) {
        WorldPrograms p=new WorldPrograms(ledger,port);JsonObject draft=new JsonObject();draft.addProperty("program",id);draft.add("body",body);draft.addProperty("_operation_id","draft-"+id);p.draft(AgentRole.PHANES,draft);p.validate(id,1);return p;
    }
    private JsonObject call(String id){JsonObject a=new JsonObject();a.addProperty("program",id);a.addProperty("version",1);return a;}
    private void complete(WorldPrograms p){for(int i=0;i<300;i++)p.tick();}
    @Test void draftIsImmutableAndCannotPublishWithoutTrial() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);WorldPrograms p=setup(l,fake,"health",readBody);assertThrows(IllegalArgumentException.class,()->p.publish("health",1));String hash=p.version("health",1).get("hash").getAsString();p.start(AgentRole.PHANES,call("health"),"07010001",true);complete(p);assertEquals(List.of("DONE"),fake.outcomes);p.publish("health",1);assertEquals(hash,p.version("health",1).get("hash").getAsString());assertEquals(0,fake.dispatches);
    }
    @Test void paginationForeachVisitsAllPagesAndEveryMutationHasOneStableId() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);JsonObject body=V070GovernanceTest.json("{steps:[{op:'foreach',query:{type:'entities',world:'world'},as:'entity',steps:[{op:'action',action:{type:'set_time',world:'world',ticks:'$entity.index'}}]}],assertions:[{left:true,compare:'eq',right:true}]}");WorldPrograms p=setup(l,fake,"pages",body);p.start(AgentRole.PHANES,call("pages"),"07010002",true);complete(p);assertEquals(25,fake.dispatches);assertEquals("DONE",l.section("program_runs").getAsJsonObject("07010002").get("state").getAsString());p.tick();assertEquals(25,fake.dispatches);
    }
    @Test void pendingCursorResumesAfterRestartWithoutRepeatingAppliedMutation() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);WorldPrograms p=setup(l,fake,"once",V070GovernanceTest.json("{steps:[{op:'action',action:{type:'set_time',world:'world',ticks:12}}],assertions:[{left:true,compare:'eq',right:true}]}"));p.start(AgentRole.PHANES,call("once"),"07010003",true);for(int i=0;i<5;i++)p.tick();assertEquals(1,fake.dispatches);
        GovernanceLedger reload=new GovernanceLedger(folder);Fake second=new Fake(reload);complete(new WorldPrograms(reload,second));assertEquals(0,second.dispatches);assertEquals("DONE",reload.section("program_runs").getAsJsonObject("07010003").get("state").getAsString());
    }
    @Test void failedProgramDisablesVersionAndDoesNotClaimSuccess() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);fake.reject=true;WorldPrograms p=setup(l,fake,"failure",V070GovernanceTest.json("{steps:[{op:'action',action:{type:'set_time',world:'world',ticks:12}}],assertions:[{left:true,compare:'eq',right:true}]}"));p.start(AgentRole.PHANES,call("failure"),"07010004",true);complete(p);assertEquals("DISABLED",p.version("failure",1).get("state").getAsString());assertEquals(List.of("FAILED"),fake.outcomes);assertThrows(IllegalArgumentException.class,()->p.publish("failure",1));
    }
    @Test void blockTrialRestoresBeforePublication() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);WorldPrograms p=setup(l,fake,"edit",V070GovernanceTest.json("{steps:[{op:'action',action:{type:'set_blocks',world:'world',x1:0,y1:70,z1:0,x2:0,y2:70,z2:0,material:'STONE'}}],assertions:[{left:true,compare:'eq',right:true}]}"));p.start(AgentRole.PHANES,call("edit"),"07010005",true);complete(p);assertEquals(2,fake.dispatches);assertEquals("DONE",l.section("program_runs").getAsJsonObject("07010005").get("state").getAsString());assertTrue(l.section("program_runs").getAsJsonObject("07010005").get("restored").getAsBoolean());p.publish("edit",1);
    }
    @Test void pausePreservesCursorAndPinnedChildVersionChecksItsAssertions() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);WorldPrograms p=setup(l,fake,"child",readBody);p.start(AgentRole.PHANES,call("child"),"07010006",true);fake.paused=true;complete(p);assertTrue(fake.outcomes.isEmpty());fake.paused=false;complete(p);p.publish("child",1);
        JsonObject parent=V070GovernanceTest.json("{steps:[{op:'call',program:'child',version:1},{op:'return',value:'$tps.data.tps'}],assertions:[{left:'$tps.data.tps',compare:'eq',right:20}]}");JsonObject draft=new JsonObject();draft.addProperty("program","parent");draft.add("body",parent);p.draft(AgentRole.PHANES,draft);p.validate("parent",1);p.start(AgentRole.PHANES,call("parent"),"07010007",true);complete(p);assertEquals("DONE",l.section("program_runs").getAsJsonObject("07010007").get("state").getAsString());
    }
    @Test void unsafeInstructionsIndirectCommandsAndInventedVariablesAreRejected() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);WorldPrograms p=new WorldPrograms(l,fake);
        for(String action:List.of("file_read","network_request","host_command","op","minecraft_command","grant_capability","run_program")){JsonObject a=V070GovernanceTest.json("{program:'unsafe',body:{steps:[{op:'action',action:{type:'"+action+"'}}],assertions:[{left:true,compare:'eq',right:true}]}}");assertThrows(IllegalArgumentException.class,()->p.draft(AgentRole.PHANES,a));}
        assertThrows(IllegalArgumentException.class,()->WorldPrograms.resolve(com.google.gson.JsonParser.parseString("\"$unknown.x\""),new JsonObject()));assertThrows(IllegalArgumentException.class,()->WorldPrograms.condition(V070GovernanceTest.json("{left:'20',compare:'gt',right:1}"),new JsonObject()));
    }
    @Test void selfRecursionViaAnExistingVersionIsRejected() {
        GovernanceLedger l=new GovernanceLedger(folder);Fake fake=new Fake(l);WorldPrograms p=setup(l,fake,"recursive",readBody);p.start(AgentRole.PHANES,call("recursive"),"07010008",true);complete(p);p.publish("recursive",1);
        JsonObject modified=V070GovernanceTest.json("{steps:[{op:'call',program:'recursive',version:1}],assertions:[{left:true,compare:'eq',right:true}]}");JsonObject original=p.version("recursive",1);original.add("body",modified);assertThrows(IllegalStateException.class,()->p.validate("recursive",1));original.addProperty("hash",WorldPrograms.hash(modified.toString()));assertThrows(IllegalArgumentException.class,()->p.validate("recursive",1));
    }
}
