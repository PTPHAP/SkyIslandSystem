package dev.skyisland;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V070GovernanceTest {
    @TempDir Path folder;
    static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    @Test void sameActionDifferentTargetAndRuleAreDifferentCapabilities() {
        CapabilityGrants grants=new CapabilityGrants(folder);
        assertEquals("",grants.check(AgentRole.ISTAROTH,json("{type:'set_gamerule',rule:'doDaylightCycle'}")));
        assertFalse(grants.check(AgentRole.ISTAROTH,json("{type:'set_gamerule',rule:'doMobSpawning'}")).isBlank());
        assertEquals("",grants.check(AgentRole.RONOVA,json("{type:'remove_entity',_target_type:'MONSTER'}")));
        assertThrows(IllegalArgumentException.class,()->grants.check(AgentRole.PHANES,json("{type:'remove_entity',_target_type:'PLAYER'}")));
        assertEquals("entity.spawn.cow",CapabilityCatalog.resolve(json("{type:'spawn_entity',entity:'COW'}")).id());
    }
    @Test void grantChecksWorldRegionCaseExpiryAndRevocationAfterRestart() {
        CapabilityGrants grants=new CapabilityGrants(folder);JsonObject grant=json("{role:'ronova',capability:'region.edit',world:'world',case_id:'abc',minutes:1,region:{x1:0,y1:60,z1:0,x2:15,y2:80,z2:15}}");
        grants.grant(grant,"07000001",true);JsonObject a=json("{type:'set_blocks',world:'world',case_id:'abc',x1:0,y1:64,z1:0,x2:1,y2:64,z2:1}");
        assertEquals("",new CapabilityGrants(folder).check(AgentRole.RONOVA,a));
        for(String field:java.util.List.of("world","case_id")){JsonObject bad=a.deepCopy();bad.addProperty(field,"other");assertFalse(grants.check(AgentRole.RONOVA,bad).isBlank());}
        JsonObject bad=a.deepCopy();bad.addProperty("x2",16);assertFalse(grants.check(AgentRole.RONOVA,bad).isBlank());
        grants.revoke("07000001");assertFalse(new CapabilityGrants(folder).check(AgentRole.RONOVA,a).isBlank());
        grants.grant(grant,"07000002",true);JsonObject saved=JsonState.read(folder.resolve("capabilities.json"));saved.getAsJsonObject("grants").getAsJsonObject("07000002").addProperty("expires",1);JsonState.write(folder.resolve("capabilities.json"),saved);
        assertFalse(new CapabilityGrants(folder).check(AgentRole.RONOVA,a).isBlank());
    }
    @Test void explicitDenyCanBeRestoredByPhanesAndNeverCountsAsDisobedience() {
        CapabilityGrants grants=new CapabilityGrants(folder);JsonObject grant=json("{role:'istaroth',capability:'time.set'}"),a=json("{type:'set_time',world:'world',ticks:0}");
        grants.grant(grant,"07000003",false);assertFalse(grants.check(AgentRole.ISTAROTH,a).isBlank());
        grants.grant(grant,"07000004",true);assertEquals("",new CapabilityGrants(folder).check(AgentRole.ISTAROTH,a));
        assertEquals("",new ShadowDiscipline(folder).suspension(AgentRole.ISTAROTH));
    }
    @Test void legacyCustomScopeIsBackedUpAndMigratedWithoutBroadeningDefaults() throws Exception {
        Files.writeString(folder.resolve("shadow-discipline.properties"),"version=3\nronova.scope=remove_entity,set_border,minecraft_command,set_law,punish_player,confiscate_item,restore_items\n");
        CapabilityGrants grants=new CapabilityGrants(folder);
        assertEquals("",grants.check(AgentRole.RONOVA,json("{type:'set_border',world:'world',size:500}")));
        assertFalse(grants.check(AgentRole.RONOVA,json("{type:'relieve_entity_pressure',_target_type:'OLD_ITEM'}")).isBlank());
        try(var files=Files.list(folder)){assertTrue(files.anyMatch(p->p.getFileName().toString().startsWith("shadow-discipline.pre-v070-")));}
        assertEquals("",grants.check(AgentRole.ISTAROTH,json("{type:'set_time'}")));
    }
    @Test void continuingNewEvidenceHasNoFixedRoundLimitButStallsRequireANewMethod() {
        GovernanceLedger ledger=new GovernanceLedger(folder);String id=ledger.open(null,"entity-MONSTER",null,"actual");InvestigationProgress progress=new InvestigationProgress(ledger);JsonObject q=json("{type:'entities',world:'world'}");
        for(int i=0;i<25;i++){JsonObject result=json("{data:{count:0},sampled_at:1,evidence_id:'x'}");result.getAsJsonObject("data").addProperty("count",i);assertEquals("CONTINUE",progress.observe(id,AgentRole.RONOVA,q,result));}
        JsonObject result=json("{data:{count:24},sampled_at:99,evidence_id:'y'}");assertEquals("CONTINUE",progress.observe(id,AgentRole.RONOVA,q,result));InvestigationProgress resumed=new InvestigationProgress(new GovernanceLedger(folder));assertEquals("CHANGE_METHOD",resumed.observe(id,AgentRole.RONOVA,q,result));assertEquals("WAIT_REVIEW",resumed.observe(id,AgentRole.RONOVA,q,result));
    }
    @Test void experienceUsesActualReceiptsRetainsFailuresAndIsRoleIsolated() {
        GovernanceLedger ledger=new GovernanceLedger(folder);String id=ledger.open(null,"entity-MONSTER",null,"count=120");GovernanceExperience experience=new GovernanceExperience(ledger);JsonObject a=json("{type:'remove_entity'}");a.addProperty("case_id",id);
        experience.learn("07000005",AgentRole.RONOVA,a,"REJECTED","protected entity");experience.learn("07000005",AgentRole.RONOVA,a,"DONE","actually removed 1");experience.learn("07000005",AgentRole.RONOVA,a,"DONE","duplicate replay");
        assertEquals(2,experience.find(AgentRole.RONOVA,"entity-MONSTER","").size());assertTrue(experience.find(AgentRole.NABERIUS,"entity-MONSTER","").isEmpty());assertTrue(experience.find(AgentRole.RONOVA,"unrelated","").isEmpty());assertEquals(2,new GovernanceExperience(new GovernanceLedger(folder)).find(AgentRole.RONOVA,"","remove_entity").size());
    }
    @Test void caseBookkeepingAndNewOpinionsDoNotPretendToBeNewFacts() {
        GovernanceLedger ledger=new GovernanceLedger(folder);String id=ledger.open(null,"investigation",null,"measured hotspot");
        InvestigationProgress progress=new InvestigationProgress(ledger);JsonObject q=json("{type:'case_evidence',offset:0}");q.addProperty("case_id",id);
        JsonObject result=json("{data:{case:{facts:'measured hotspot',updated:1,phase:'INVESTIGATING'},items:[{kind:'fact',text:'count=100'}]},total:1,next:-1,complete:true}");
        assertEquals("CONTINUE",progress.observe(id,AgentRole.RONOVA,q,result));
        for(int i=1;i<=3;i++) {
            result.getAsJsonObject("data").getAsJsonObject("case").addProperty("updated",i+1);
            result.getAsJsonObject("data").getAsJsonArray("items").add(json("{kind:'judgment',text:'opinion "+i+"'}"));result.addProperty("total",i+1);
            assertEquals(i==1?"CONTINUE":i==2?"CHANGE_METHOD":"WAIT_REVIEW",progress.observe(id,AgentRole.RONOVA,q,result));
        }
        result.getAsJsonObject("data").getAsJsonArray("items").add(json("{kind:'execution',text:'removed 20'}"));
        assertEquals("CONTINUE",progress.observe(id,AgentRole.RONOVA,q,result));
    }
    @Test void structuredPaginationDoesNotPretendTheFirstTwentyRowsAreAllEvidence() {
        java.util.List<JsonObject> rows=new java.util.ArrayList<>();for(int i=0;i<25;i++)rows.add(json("{n:"+i+"}"));JsonObject first=WorldInvestigation.page(rows,new JsonObject());assertEquals(20,first.get("next").getAsInt());assertFalse(first.get("complete").getAsBoolean());JsonObject last=WorldInvestigation.page(rows,json("{offset:20}"));assertEquals(5,last.getAsJsonObject("data").getAsJsonArray("items").size());assertEquals(-1,last.get("next").getAsInt());
    }
    @Test void malformedOrExternalCapabilitiesCannotBeGranted() {
        CapabilityGrants grants=new CapabilityGrants(folder);for(String cap:java.util.List.of("op","file.read","host.exec","minecraft_command"))assertThrows(IllegalArgumentException.class,()->grants.validate(json("{role:'ronova',capability:'"+cap+"'}")));
        assertThrows(IllegalArgumentException.class,()->grants.validate(json("{role:'ronova',capability:'time.set',region:{x1:0,y1:0,z1:0,x2:1,y2:1,z2:1}}")));
    }
}
