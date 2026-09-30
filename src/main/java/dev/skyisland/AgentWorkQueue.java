package dev.skyisland;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;

/** Durable requests; delivery runs on the Paper thread with a stable operation ID. */
final class AgentWorkQueue {
    interface Recovery { void accept(AgentRole role, String task, String caseContext, String mode, AgentReply reply); }
    private final SkyIslandPlugin plugin;
    private final OpenClawClient client;
    private final Path file;
    private final JsonObject jobs;
    private final Map<String, BiConsumer<AgentReply, Throwable>> callbacks = new HashMap<>();
    private final Set<String> running = new HashSet<>();
    private final Set<AgentRole> actors = new HashSet<>();
    private final Recovery recovery;
    AgentWorkQueue(SkyIslandPlugin plugin, OpenClawClient client, Recovery recovery) {
        this.plugin = plugin; this.client = client; this.recovery = recovery;
        file = plugin.getDataFolder().toPath().resolve("agent-jobs.json"); jobs = JsonState.read(file);
    }
    void submit(AgentRole role, String prompt, String mode, BiConsumer<AgentReply, Throwable> callback) {
        submit(role, prompt, "", "", mode, callback);
    }
    void submit(AgentRole role, String prompt, String task, String caseContext, String mode, BiConsumer<AgentReply, Throwable> callback) {
        if (jobs.entrySet().stream().anyMatch(e -> {
            JsonObject j = e.getValue().getAsJsonObject();
            return j.get("role").getAsString().equals(role.id) && j.get("prompt").getAsString().equals(prompt)
                && j.get("mode").getAsString().equals(mode);
        })) return;
        if (jobs.size() >= 200) throw new IllegalStateException("AI 队列已满，案件保留待复查");
        String id;
        do{id=UUID.randomUUID().toString().substring(0,8);}while(jobs.has(id) || plugin.knownOperation(id));
        JsonObject j = new JsonObject(); j.addProperty("role", role.id); j.addProperty("prompt", prompt);
        j.addProperty("task", task); j.addProperty("case_context", caseContext);
        j.addProperty("mode", mode); j.addProperty("next", 0); jobs.add(id, j); save(); callbacks.put(id, callback);
    }
    void tick() {
        if (!client.configured() || plugin.aiPaused()) return;
        for (String id : java.util.List.copyOf(jobs.keySet())) {
            if (running.size() >= 2) break;
            JsonObject job = jobs.getAsJsonObject(id);
            AgentRole role = AgentRole.parse(job.get("role").getAsString());
            if (actors.contains(role) || running.contains(id) || job.get("next").getAsLong() > System.currentTimeMillis()) continue;
            actors.add(role); running.add(id);
            if (job.has("reply")) { deliver(id, AgentReply.parse(job.get("reply").getAsString()), null); continue; }
            client.ask(role, job.get("prompt").getAsString()).whenComplete((reply, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> deliver(id, reply, error));
            });
        }
    }
    private void deliver(String id, AgentReply reply, Throwable error) {
        JsonObject job = jobs.getAsJsonObject(id);
        AgentRole role = AgentRole.parse(job.get("role").getAsString());
        try {
            if (error != null) {
                plugin.gatewayFailed();
                if(job.get("mode").getAsString().equals("meeting")) {
                    BiConsumer<AgentReply,Throwable> callback=callbacks.get(id);
                    AgentReply absent=AgentReply.parse("{\"message\":\"缺席：网关请求失败，未形成有效意见\",\"action\":null}");
                    if(callback!=null)callback.accept(absent,error);else recover(role,job,absent);
                    jobs.remove(id);callbacks.remove(id);save();return;
                }
                int failures=job.has("failures")?job.get("failures").getAsInt()+1:1;
                job.addProperty("failures", failures);
                job.addProperty("state",failures>=6?"WAIT_GATEWAY":"RETRY");
                job.addProperty("next", System.currentTimeMillis() + (failures>=6?900_000L:Math.min(300_000L,60_000L*failures))); save();
                plugin.audit("agent-deferred id=" + id + " role=" + role.id + " reason=" + error.getClass().getSimpleName());
                return;
            }
            JsonObject wire = new JsonObject(); wire.addProperty("message", reply.message());
            if (reply.validFormat()) {
                if (reply.action() != null) { wire.add("action", reply.action().deepCopy()); wire.getAsJsonObject("action").addProperty("_operation_id", id); }
                if (reply.query() != null) wire.add("query", reply.query());
                if (!reply.delegateRole().isBlank()) { JsonObject d = new JsonObject(); d.addProperty("role", reply.delegateRole()); wire.add("delegate", d); }
                if (!reply.approvalId().isBlank()) {
                    JsonObject a = new JsonObject(); a.addProperty("id", reply.approvalId()); a.addProperty("hash", reply.approvalHash()); a.addProperty("approved", reply.approved()); wire.add("approval", a);
                }
                job.addProperty("reply", wire.toString()); save(); reply = AgentReply.parse(wire.toString());
            }
            BiConsumer<AgentReply, Throwable> callback = callbacks.get(id);
            if (callback != null) callback.accept(reply, null);
            else recover(role,job,reply);
            jobs.remove(id); callbacks.remove(id); save();
        } catch (RuntimeException failed) {
            job.addProperty("next", System.currentTimeMillis() + 60_000L); save();
            plugin.getLogger().warning("AI 工作未结算，保留重试 " + id + "：" + failed.getMessage());
        } finally { running.remove(id); actors.remove(role); }
    }
    int size() { return jobs.size(); }
    void interruptMeetings(GovernanceLedger ledger) {
        for(String id:java.util.List.copyOf(jobs.keySet())) {
            JsonObject job=jobs.getAsJsonObject(id);if(!"meeting".equals(job.get("mode").getAsString()))continue;
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("会议 ([0-9a-f]{8})").matcher(job.get("prompt").getAsString());
            String meeting=match.find()?match.group(1):id;
            if(!ledger.section("meetings").has(meeting)) {
                JsonObject record=new JsonObject();record.addProperty("minutes","旧版会议重启中断；此前意见参见audit.log");
                record.addProperty("conclusion","重启中断，未形成有效决议");record.addProperty("status","INTERRUPTED");record.addProperty("time",System.currentTimeMillis());
                ledger.section("meetings").add(meeting,record);ledger.save();
            }
            jobs.remove(id);
        }
        save();
    }
    private void recover(AgentRole role, JsonObject job, AgentReply reply) {
        String context=AgentReply.string(job,"case_context", "");
        if(!job.has("task")) {
            java.util.regex.Matcher id=java.util.regex.Pattern.compile("案件 ([0-9a-f]{8})").matcher(job.get("prompt").getAsString());
            if(id.find() && plugin.knownCase(id.group(1)))context="案件 "+id.group(1);
        }
        String task=AgentReply.string(job,"task", "重启续办。"+context+"；依据已保存证据重新调查，不能重复已完成操作。");
        recovery.accept(role,task,context,job.get("mode").getAsString(),reply);
    }
    boolean containsCase(String caseId, AgentRole role) {
        return jobs.entrySet().stream().anyMatch(e -> {
            JsonObject j=e.getValue().getAsJsonObject();
            return j.get("role").getAsString().equals(role.id) && j.get("prompt").getAsString().contains("案件 "+caseId);
        });
    }
    private void save() { JsonState.write(file, jobs); }
}
