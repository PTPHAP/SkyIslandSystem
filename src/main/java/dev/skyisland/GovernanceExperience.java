package dev.skyisland;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Experience is sourced from durable receipts. A model cannot invent a successful result. */
final class GovernanceExperience {
    private final GovernanceLedger ledger;
    GovernanceExperience(GovernanceLedger ledger){this.ledger=ledger;}
    void learn(String operation,AgentRole role,JsonObject action,String state,String result) {
        String key=operation+":"+state;
        if(ledger.section("experience").has(key)||java.util.Set.of("RUNNING","PAUSED","WAIT_APPROVAL").contains(state))return;
        JsonObject entry=new JsonObject();String id=AgentReply.string(action,"case_id","");
        entry.addProperty("role",role.id);entry.addProperty("operation",operation);entry.addProperty("case_id",id);entry.addProperty("state",state);entry.addProperty("result",result);
        entry.add("method",action.deepCopy());entry.addProperty("signal",!id.isBlank()&&ledger.section("cases").has(id)?ledger.requireCase(id).get("signal").getAsString():action.get("type").getAsString());
        entry.addProperty("evidence",!id.isBlank()&&ledger.section("cases").has(id)?ledger.requireCase(id).get("facts").getAsString():"仅有操作回执，尚无案件证据");entry.addProperty("time",System.currentTimeMillis());
        ledger.section("experience").add(key,entry);ledger.save();
    }
    List<JsonObject> find(AgentRole role,String signal,String action) {
        List<JsonObject> out=new ArrayList<>();for(var e:ledger.section("experience").entrySet()) {
            JsonObject x=e.getValue().getAsJsonObject();if(!x.get("role").getAsString().equals(role.id))continue;
            int relevance=(signal.equals(x.get("signal").getAsString())&&!signal.isBlank()?4:0)+(action.equals(AgentReply.string(x.getAsJsonObject("method"),"type",""))&&!action.isBlank()?2:0);
            if(relevance==0 && (!signal.isBlank()||!action.isBlank()))continue;
            JsonObject row=x.deepCopy();row.addProperty("relevance",relevance);out.add(row);
        }
        out.sort(Comparator.comparingInt((JsonObject x)->x.get("relevance").getAsInt()).reversed().thenComparing(Comparator.comparingLong((JsonObject x)->x.get("time").getAsLong()).reversed()));return out;
    }
}
