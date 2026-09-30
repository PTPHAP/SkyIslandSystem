package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class GovernanceLedger {
    private final Path folder;
    private final JsonObject index;
    GovernanceLedger(Path folder) {
        this.folder = folder.resolve("governance");
        index = JsonState.read(this.folder.resolve("index.json"));
        if(index.has("version") && index.get("version").getAsInt()!=1)throw new IllegalStateException("治理档案版本不受支持，保留原文件；请使用对应插件版本");
        for (String name : List.of("cases", "operations", "notes", "meetings", "profiles", "sanctions", "escrow", "programs", "program_runs", "experience", "activities", "region_edits", "investigations"))
            if (!index.has(name)) index.add(name, new JsonObject());
        index.addProperty("version", 1);
    }
    JsonObject section(String name) { return index.getAsJsonObject(name); }
    void save() { JsonState.write(folder.resolve("index.json"), index); }
    void checkpoint(String id, JsonObject value) {
        if (!id.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("操作编号无效");
        JsonState.write(folder.resolve("operations").resolve(id + ".json"), value);
    }
    JsonObject checkpoint(String id) {
        if (!id.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("操作编号无效");
        return JsonState.read(folder.resolve("operations").resolve(id + ".json"));
    }
    JsonObject requireCase(String id) {
        if (!section("cases").has(id)) throw new IllegalArgumentException("案件不存在，请查询真实案件编号");
        return section("cases").getAsJsonObject(id);
    }
    String open(String id, String signal, UUID subject, String facts) {
        if (id == null)do{id=UUID.randomUUID().toString().substring(0,8);}while(section("cases").has(id));
        if (section("cases").has(id)) return id;
        JsonObject c = new JsonObject();
        c.addProperty("id", id); c.addProperty("signal", signal);
        c.addProperty("subject", subject == null ? "" : subject.toString());
        c.addProperty("created", System.currentTimeMillis()); c.addProperty("status", "OPEN");
        c.addProperty("facts", facts); c.add("history", new JsonArray());
        section("cases").add(id, c); save(); return id;
    }
    void record(String id, String kind, String actor, String text) {
        if (id.isBlank()) return;
        JsonObject c = requireCase(id), entry = new JsonObject();
        entry.addProperty("time", System.currentTimeMillis()); entry.addProperty("kind", kind);
        entry.addProperty("actor", actor); entry.addProperty("text", text);
        c.getAsJsonArray("history").add(entry); c.addProperty("updated", System.currentTimeMillis()); save();
    }
    void status(String id, String status) {
        if("CLOSED".equals(requireCase(id).get("status").getAsString()) && !java.util.Set.of("CLOSED","APPEAL_PENDING").contains(status))return;
        requireCase(id).addProperty("status", status); requireCase(id).addProperty("updated", System.currentTimeMillis()); save();
    }
    String caseSummary(String id, boolean full) {
        JsonObject c = document(id);
        return full ? c.toString() : "案件 " + id + " 信号=" + c.get("signal").getAsString()
            + " 状态=" + c.get("status").getAsString() + "；事实=" + c.get("facts").getAsString()
            + "；记录=" + c.get("history");
    }
    String caseEvidence(String id, int offset) {
        JsonObject c=document(id);JsonArray history=c.getAsJsonArray("history"),page=new JsonArray();
        if(offset<0 || offset>history.size())throw new IllegalArgumentException("分页 offset 超出范围");
        int end=Math.min(offset+10,history.size());
        for(int i=offset;i<end;i++)page.add(history.get(i));
        c.add("history",page);c.addProperty("total",history.size());c.addProperty("offset",offset);c.addProperty("next",end<history.size()?end:-1);
        return c.toString();
    }
    private JsonObject document(String id) {
        JsonObject c=requireCase(id).deepCopy();
        for(String key:java.util.List.copyOf(c.keySet()))if(key.startsWith("budget_") || key.startsWith("runtime_") || key.equals("lastResume"))c.remove(key);
        for(String name:java.util.List.of("sanctions","escrow")) {
            JsonObject related=new JsonObject();
            for(var entry:section(name).entrySet())if(id.equals(AgentReply.string(entry.getValue().getAsJsonObject(),"case_id", ""))) {
                JsonObject record=entry.getValue().getAsJsonObject().deepCopy();record.remove("items");related.add(entry.getKey(),record);
            }
            c.add(name,related);
        }
        return c;
    }
    void share(String id, AgentRole role) {
        if(role==AgentRole.PHANES)return;
        JsonObject c=requireCase(id);JsonArray readers=c.has("readers")?c.getAsJsonArray("readers"):new JsonArray();
        if(java.util.stream.StreamSupport.stream(readers.spliterator(),false).anyMatch(e->e.getAsString().equals(role.id)))return;
        readers.add(role.id);c.add("readers",readers);save();
    }
    boolean readable(String id, AgentRole role) {
        JsonObject c=requireCase(id);
        return role==AgentRole.PHANES || c.has("readers") && java.util.stream.StreamSupport.stream(c.getAsJsonArray("readers").spliterator(),false).anyMatch(e->e.getAsString().equals(role.id));
    }
    List<String> ownCases(UUID player) {
        return section("cases").entrySet().stream().filter(e -> e.getValue().getAsJsonObject()
            .get("subject").getAsString().equals(player.toString())).map(e -> e.getKey()).toList();
    }
    String note(AgentRole role, JsonObject action) {
        String text = WorldActions.string(action, "text");
        if (text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("记忆笔记须为 1..2000 字");
        String source = AgentReply.string(action, "case_id", "");
        if (!source.isBlank() && !readable(source,role))throw new IllegalArgumentException("案件未共享给本角色");
        if (!section("notes").has(role.id)) section("notes").add(role.id, new JsonArray());
        JsonArray notes = section("notes").getAsJsonArray(role.id);
        String operation=AgentReply.string(action,"_operation_id", "");
        if(!operation.isBlank())for(var existing:notes)if(operation.equals(AgentReply.string(existing.getAsJsonObject(),"operation", "")))return "此私人笔记已保存";
        JsonObject n = new JsonObject(); n.addProperty("text", text); n.addProperty("source", source);
        n.addProperty("operation",operation);
        n.addProperty("time", System.currentTimeMillis()); notes.add(n); save(); return "私人笔记已保存；角色=" + role.id;
    }
    String notes(AgentRole role, int offset) {
        JsonArray notes = section("notes").has(role.id) ? section("notes").getAsJsonArray(role.id) : new JsonArray();
        JsonArray page = new JsonArray();
        for (int i = Math.max(0, notes.size() - 10 - offset); i < Math.max(0, notes.size() - offset); i++) page.add(notes.get(i));
        return "角色=" + role.id + " total=" + notes.size() + " offset=" + offset + " next="
            + (offset + 10 < notes.size() ? offset + 10 : -1) + " notes=" + page;
    }
    void operation(String id, JsonObject action, AgentRole actor, String state, String result) {
        JsonObject o = new JsonObject(); o.addProperty("actor", actor.id); o.addProperty("state", state);
        o.addProperty("updated", System.currentTimeMillis()); o.add("action", action.deepCopy()); o.addProperty("result", result);
        section("operations").add(id, o); save();
    }
    String operations(int offset) {
        return operations(offset,AgentRole.PHANES);
    }
    String operations(int offset, AgentRole role) {
        List<String> entries = section("operations").entrySet().stream()
            .filter(e->role==AgentRole.PHANES || e.getValue().getAsJsonObject().get("actor").getAsString().equals(role.id)
                || !AgentReply.string(e.getValue().getAsJsonObject().getAsJsonObject("action"),"case_id", "").isBlank()
                && section("cases").has(AgentReply.string(e.getValue().getAsJsonObject().getAsJsonObject("action"),"case_id", ""))
                && readable(AgentReply.string(e.getValue().getAsJsonObject().getAsJsonObject("action"),"case_id", ""),role))
            .sorted(Comparator.comparingLong((java.util.Map.Entry<String, com.google.gson.JsonElement> e) ->
                e.getValue().getAsJsonObject().get("updated").getAsLong()).reversed())
            .map(e -> e.getKey() + ":" + e.getValue()).toList();
        return page(entries, offset);
    }
    static String page(List<String> values, int offset) {
        if (offset < 0 || offset > values.size()) throw new IllegalArgumentException("分页 offset 超出范围");
        int end = Math.min(offset + 10, values.size());
        return "total=" + values.size() + " offset=" + offset + " next=" + (end < values.size() ? end : -1)
            + "\n" + String.join("\n", values.subList(offset, end));
    }
    void prune() {
        long cutoff = System.currentTimeMillis() - 90L * 86_400_000L;
        boolean changed = section("cases").entrySet().removeIf(e -> {
            JsonObject c = e.getValue().getAsJsonObject();
            if (!"CLOSED".equals(c.get("status").getAsString()) || c.get("updated").getAsLong() >= cutoff) return false;
            String id = e.getKey();
            return section("sanctions").entrySet().stream().noneMatch(s -> id.equals(AgentReply.string(s.getValue().getAsJsonObject(), "case_id", "")))
                && section("escrow").entrySet().stream().noneMatch(s -> id.equals(AgentReply.string(s.getValue().getAsJsonObject(), "case_id", "")));
        });
        if (changed) save();
    }
}
