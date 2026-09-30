package dev.skyisland;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GovernanceLedgerTest {
    @TempDir Path folder;
    @Test void durableCasesKeepMeasurementsJudgmentsAndCorrectionsSeparate() {
        GovernanceLedger ledger=new GovernanceLedger(folder);UUID subject=UUID.randomUUID(),other=UUID.randomUUID();
        String id=ledger.open(null,"tnt",subject,"measured=33");
        ledger.record(id,"judgment","phanes","temporary-ban");ledger.record(id,"correction","phanes","pardon");
        ledger.status(id,"APPEAL_PENDING");
        GovernanceLedger reloaded=new GovernanceLedger(folder);
        assertEquals(java.util.List.of(id),reloaded.ownCases(subject));assertTrue(reloaded.ownCases(other).isEmpty());
        JsonObject c=reloaded.requireCase(id);assertEquals("measured=33",c.get("facts").getAsString());
        assertEquals("judgment",c.getAsJsonArray("history").get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("correction",c.getAsJsonArray("history").get(1).getAsJsonObject().get("kind").getAsString());
        assertEquals("APPEAL_PENDING",c.get("status").getAsString());
    }
    @Test void notesAreIsolatedAndReplyReplayDoesNotAppendTwice() {
        GovernanceLedger ledger=new GovernanceLedger(folder);JsonObject note=new JsonObject();note.addProperty("text","only ronova knows this");note.addProperty("_operation_id","0123abcd");
        ledger.note(AgentRole.RONOVA,note);ledger.note(AgentRole.RONOVA,note);
        assertTrue(ledger.notes(AgentRole.RONOVA,0).contains("total=1"));assertFalse(ledger.notes(AgentRole.NABERIUS,0).contains("only ronova"));
        assertTrue(new GovernanceLedger(folder).notes(AgentRole.RONOVA,0).contains("only ronova"));
    }
    @Test void malformedStorageAndPathEscapesFailClosed() throws Exception {
        Files.createDirectories(folder.resolve("governance"));Files.writeString(folder.resolve("governance/index.json"),"broken");
        assertThrows(IllegalStateException.class,()->new GovernanceLedger(folder));
        GovernanceLedger clean=new GovernanceLedger(folder.resolve("clean"));
        assertThrows(IllegalArgumentException.class,()->clean.checkpoint("../../ops",new JsonObject()));
        assertThrows(IllegalArgumentException.class,()->clean.checkpoint("../../ops"));
    }
    @Test void caseEvidenceExplicitlyPagesAndReportsRemainingRecords() {
        GovernanceLedger ledger=new GovernanceLedger(folder);String id=ledger.open(null,"tnt",null,"facts");
        for(int i=0;i<12;i++)ledger.record(id,"measurement","system","record="+i);
        JsonObject first=JsonParser.parseString(ledger.caseEvidence(id,0)).getAsJsonObject();
        assertEquals(12,first.get("total").getAsInt());assertEquals(10,first.get("next").getAsInt());assertEquals(10,first.getAsJsonArray("history").size());
        JsonObject second=JsonParser.parseString(ledger.caseEvidence(id,10)).getAsJsonObject();
        assertEquals(-1,second.get("next").getAsInt());assertEquals(2,second.getAsJsonArray("history").size());
    }
    @Test void roleCaseSharingDoesNotExposePrivateNotesOrRuntimePrompts() {
        GovernanceLedger ledger=new GovernanceLedger(folder);String id=ledger.open(null,"tnt",null,"facts");
        assertFalse(ledger.readable(id,AgentRole.RONOVA));ledger.share(id,AgentRole.RONOVA);
        assertTrue(ledger.readable(id,AgentRole.RONOVA));assertFalse(ledger.readable(id,AgentRole.ASMODAY));
        JsonObject budget=new JsonObject();budget.addProperty("resume","PRIVATE_RUNTIME_PROMPT");ledger.requireCase(id).add("budget_phanes",budget);
        assertFalse(ledger.caseEvidence(id,0).contains("PRIVATE_RUNTIME_PROMPT"));
        assertFalse(ledger.caseSummary(id,true).contains("PRIVATE_RUNTIME_PROMPT"));
    }
    @Test void numericBoundsRejectFractionalOverflowAndMissingValues() {
        for(String value:new String[]{"1.5","1e100","\"NaN\"","true","null"}) {
            JsonObject a=JsonParser.parseString("{\"minutes\":"+value+"}").getAsJsonObject();
            assertThrows(IllegalArgumentException.class,()->WorldActions.number(a,"minutes",1,1440));
        }
        assertThrows(IllegalArgumentException.class,()->WorldActions.number(new JsonObject(),"minutes",1,1440));
    }
    @Test void commandTranslationKeepsWorldAndExactTargetsButCannotDispatchCommands() {
        JsonObject a=JsonParser.parseString("{\"world\":\"world_nether\",\"command\":\"fill 0 64 0 15 79 15 stone\"}").getAsJsonObject();
        JsonObject result=VanillaCommands.translate(a);assertEquals("world_nether",result.get("world").getAsString());
        assertEquals("set_blocks",result.get("type").getAsString());assertFalse(result.has("command"));
        for(String value:new String[]{"time set 0\nstop","tp @a 0 64 0","give Alex command_block{BlockEntityTag:{Command:stop}} 1","time set 0 extra"}) {
            a.addProperty("command",value);assertThrows(IllegalArgumentException.class,()->VanillaCommands.translate(a));
        }
    }
}
