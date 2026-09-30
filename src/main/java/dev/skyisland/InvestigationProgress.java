package dev.skyisland;

import com.google.gson.JsonObject;

/** Stall detection counts unchanged evidence, not model turns. Persisted across restart. */
final class InvestigationProgress {
    private final GovernanceLedger ledger;
    InvestigationProgress(GovernanceLedger ledger){this.ledger=ledger;}
    String observe(String caseId,AgentRole role,JsonObject query,JsonObject result) {
        JsonObject clean=result.deepCopy();for(String key:java.util.List.of("sampled_at","evidence_id"))clean.remove(key);
        if(AgentReply.string(query,"type","").equals("case_evidence") && clean.has("data") && clean.getAsJsonObject("data").has("case")) {
            JsonObject data=clean.getAsJsonObject("data"),c=data.getAsJsonObject("case");
            for(String field:java.util.List.of("updated","phase","next_step","wait_reason"))c.remove(field);
            if(data.has("items")) {
                com.google.gson.JsonArray facts=new com.google.gson.JsonArray();
                for(var row:data.getAsJsonArray("items"))if(!AgentReply.string(row.getAsJsonObject(),"kind","").equals("judgment"))facts.add(row);
                data.add("items",facts);
            }
            // Opinion count and its page size are bookkeeping, not new measured evidence.
            clean.remove("total");clean.remove("next");clean.remove("complete");
        }
        String key=role.id+":"+caseId,method=WorldPrograms.hash(query.toString()),evidence=WorldPrograms.hash(clean.toString());
        JsonObject progress=ledger.section("investigations").has(key)?ledger.section("investigations").getAsJsonObject(key):new JsonObject();
        int repeats=method.equals(AgentReply.string(progress,"method",""))&&evidence.equals(AgentReply.string(progress,"evidence",""))?progress.get("repeats").getAsInt()+1:0;
        progress.addProperty("method",method);progress.addProperty("evidence",evidence);progress.addProperty("repeats",repeats);progress.addProperty("updated",System.currentTimeMillis());ledger.section("investigations").add(key,progress);
        String directive=repeats>=3?"WAIT_REVIEW":repeats>=2?"CHANGE_METHOD":"CONTINUE";
        if(!caseId.isBlank()){JsonObject c=ledger.requireCase(caseId);c.addProperty("phase","INVESTIGATING");c.addProperty("next_step",directive);c.addProperty("wait_reason",directive.equals("WAIT_REVIEW")?"重复调查无新证据，等待趋势变化或新任务":directive.equals("CHANGE_METHOD")?"请改查来源、保护状态或区域，不重复相同查询":"");}
        ledger.save();return directive;
    }
}
