package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A JSON interpreter, not a JavaScript/shell runtime. All mutations go back through the plugin. */
final class WorldPrograms {
    interface Port {
        JsonObject query(AgentRole role,JsonObject query);
        String dispatch(AgentRole role,String operation,JsonObject action,boolean trial);
        String operationState(String operation);
        JsonObject receipt(String operation);
        void preview(AgentRole role,JsonObject action,boolean trial);
        String restore(AgentRole role,String operation,String edit);
        boolean paused();
        void finished(String run,AgentRole role,String state,String result);
    }
    private final GovernanceLedger ledger;
    private final Port port;
    WorldPrograms(GovernanceLedger ledger,Port port){this.ledger=ledger;this.port=port;}
    private JsonObject programs(){return ledger.section("programs");}
    private JsonObject runs(){return ledger.section("program_runs");}
    static String identifier(JsonObject a,String key){String id=WorldActions.string(a,key);if(!id.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException(key+" 须为小写工具编号，不是路径");return id;}
    JsonObject version(String id,int version){JsonObject p=programs().getAsJsonObject(id);if(p==null||!p.getAsJsonObject("versions").has(Integer.toString(version)))throw new IllegalArgumentException("程序版本不存在");JsonObject v=p.getAsJsonObject("versions").getAsJsonObject(Integer.toString(version));if(!hash(v.get("body").toString()).equals(v.get("hash").getAsString()))throw new IllegalStateException("程序内容与固定哈希不符，保留档案并停止");return v;}
    String draft(AgentRole role,JsonObject a) {
        String id=identifier(a,"program");JsonObject body=a.getAsJsonObject("body");validateBody(body,new HashSet<>(),0);
        JsonObject p=programs().has(id)?programs().getAsJsonObject(id):new JsonObject();
        if(!p.has("versions")){p.add("versions",new JsonObject());p.addProperty("active",0);}
        String operation=AgentReply.string(a,"_operation_id","");
        for(var e:p.getAsJsonObject("versions").entrySet())if(operation.equals(AgentReply.string(e.getValue().getAsJsonObject(),"operation","?")))return "草稿已存在："+id+"@"+e.getKey();
        int v=p.getAsJsonObject("versions").size()+1;JsonObject d=new JsonObject();d.add("body",body.deepCopy());d.addProperty("hash",hash(body.toString()));d.addProperty("author",role.id);d.addProperty("state","DRAFT");d.addProperty("operation",operation);d.addProperty("created",System.currentTimeMillis());
        JsonArray required=new JsonArray();requirements(body.getAsJsonArray("steps"),new java.util.TreeSet<>()).forEach(required::add);d.add("required_capabilities",required);
        p.getAsJsonObject("versions").add(Integer.toString(v),d);programs().add(id,p);ledger.save();return "不可变草稿已保存："+id+"@"+v+" hash="+d.get("hash").getAsString();
    }
    String validate(String id,int v){JsonObject d=version(id,v);validateBody(d.getAsJsonObject("body"),Set.of(id+"@"+v),0);d.addProperty("validated",true);ledger.save();return "静态校验通过；仍须只读预演和安全区试运行，不能直接发布";}
    private void validateBody(JsonObject body,Set<String> chain,int depth) {
        if(body==null || depth>8 || body.toString().length()>48_000)throw new IllegalArgumentException("程序缺失或超出深度/大小预算");
        if(!body.has("steps") || !body.get("steps").isJsonArray() || !body.has("assertions") || !body.get("assertions").isJsonArray() || body.getAsJsonArray("assertions").isEmpty())throw new IllegalArgumentException("程序需要 steps 和至少一条 assertions");
        if(body.has("parameters")){for(var value:body.getAsJsonArray("parameters"))if(!value.getAsString().matches("[a-z][a-z0-9_]{0,31}"))throw new IllegalArgumentException("参数名无效");}
        validateSteps(body.getAsJsonArray("steps"),chain,depth);
        for(var c:body.getAsJsonArray("assertions"))validateCondition(c.getAsJsonObject());
    }
    private void validateSteps(JsonArray steps,Set<String> chain,int depth) {
        if(steps.isEmpty()||steps.size()>64||depth>8)throw new IllegalArgumentException("每层 steps 为1..64，最大深度8");
        for(var value:steps) {
            JsonObject s=value.getAsJsonObject();String op=WorldActions.string(s,"op");
            switch(op) {
                case "query" -> {WorldActions.string(s,"save");JsonObject q=s.getAsJsonObject("query");if(!WorldInvestigation.TYPES.contains(WorldActions.string(q,"type")))throw new IllegalArgumentException("未知调查接口");}
                case "action" -> {
                    JsonObject a=s.getAsJsonObject("action");String type=WorldActions.string(a,"type");
                    if(a.keySet().stream().anyMatch(k->k.startsWith("_")))throw new IllegalArgumentException("程序不能伪造内部操作字段");
                    if(!CapabilityCatalog.known(type)||Set.of("minecraft_command","run_program","draft_program","validate_program","trial_program","publish_program","disable_program","switch_program","grant_capability","revoke_capability","set_shadow_scope","pardon_shadow","create_activity","end_activity","close_case","memory_note","report_defect").contains(type))throw new IllegalArgumentException("程序只能调用底层世界执行器，不能改变治理授权/程序/活动或派发命令");
                }
                case "if" -> {validateCondition(s.getAsJsonObject("condition"));for(String branch:List.of("then","else"))if(s.has(branch))validateSteps(s.getAsJsonArray(branch),chain,depth+1);}
                case "foreach" -> {WorldActions.string(s,"as");if(s.has("query")){String type=WorldActions.string(s.getAsJsonObject("query"),"type");if(!WorldInvestigation.PAGED.contains(type))throw new IllegalArgumentException("foreach query 须使用可分页接口");}else if(!s.has("items"))throw new IllegalArgumentException("foreach 需要 items 或 query");validateSteps(s.getAsJsonArray("steps"),chain,depth+1);}
                case "wait" -> WorldActions.number(s,"ticks",1,72000);
                case "call" -> {String id=identifier(s,"program");int v=(int)WorldActions.number(s,"version",1,100000);String key=id+"@"+v;if(chain.contains(key))throw new IllegalArgumentException("程序禁止递归");JsonObject d=version(id,v);if(!Set.of("PUBLISHED","RETIRED").contains(d.get("state").getAsString()))throw new IllegalArgumentException("子程序须为已发布固定版本");Set<String> next=new HashSet<>(chain);next.add(key);validateBody(d.getAsJsonObject("body"),next,depth+1);}
                case "return" -> {if(!s.has("value"))throw new IllegalArgumentException("return 缺少 value");}
                default -> throw new IllegalArgumentException("未知程序指令；不可执行字符串代码、文件、网络或主机命令");
            }
        }
    }
    private Set<String> requirements(JsonArray steps,Set<String> out) {
        for(var element:steps){JsonObject s=element.getAsJsonObject();String op=WorldActions.string(s,"op");if(op.equals("action")){JsonObject a=s.getAsJsonObject("action");try{out.add(CapabilityCatalog.resolve(a).id());}catch(IllegalArgumentException dynamic){out.addAll(CapabilityCatalog.forAction(WorldActions.string(a,"type")));}}
            for(String branch:List.of("steps","then","else"))if(s.has(branch))requirements(s.getAsJsonArray(branch),out);
            if(op.equals("call"))for(var capability:version(s.get("program").getAsString(),s.get("version").getAsInt()).getAsJsonArray("required_capabilities"))out.add(capability.getAsString());
        }return out;
    }
    static void validateCondition(JsonObject c){if(c==null||!c.has("left")||!c.has("right")||!Set.of("eq","ne","lt","le","gt","ge","contains").contains(WorldActions.string(c,"compare")))throw new IllegalArgumentException("条件需要 left/compare/right");}
    static JsonElement resolve(JsonElement value,JsonObject vars) {
        if(value==null)return JsonNull.INSTANCE;
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&value.getAsString().startsWith("$")) {
            String[] keys=value.getAsString().substring(1).split("\\.");JsonElement current=vars;
            for(String key:keys){if(!current.isJsonObject() || !current.getAsJsonObject().has(key))throw new IllegalArgumentException("程序变量不存在："+value);current=current.getAsJsonObject().get(key);}return current.deepCopy();
        }
        if(value.isJsonObject()){JsonObject out=new JsonObject();value.getAsJsonObject().entrySet().forEach(e->out.add(e.getKey(),resolve(e.getValue(),vars)));return out;}
        if(value.isJsonArray()){JsonArray out=new JsonArray();value.getAsJsonArray().forEach(e->out.add(resolve(e,vars)));return out;}
        return value.deepCopy();
    }
    static boolean condition(JsonObject c,JsonObject vars) {
        validateCondition(c);JsonElement l=resolve(c.get("left"),vars),r=resolve(c.get("right"),vars);
        return switch(c.get("compare").getAsString()) {
            case "eq" -> l.equals(r);case "ne" -> !l.equals(r);
            case "contains" -> l.isJsonArray()&&java.util.stream.StreamSupport.stream(l.getAsJsonArray().spliterator(),false).anyMatch(r::equals);
            default -> {if(!l.isJsonPrimitive()||!r.isJsonPrimitive()||!l.getAsJsonPrimitive().isNumber()||!r.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("排序比较仅接受数值");double x=l.getAsDouble(),y=r.getAsDouble();if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("数值必须有限");yield switch(c.get("compare").getAsString()){case "lt"->x<y;case "le"->x<=y;case "gt"->x>y;case "ge"->x>=y;default->false;};}
        };
    }
    String start(AgentRole role,JsonObject a,String parent,boolean trial) {
        String id=identifier(a,"program");int v=a.has("version")?(int)WorldActions.number(a,"version",1,100000):programs().getAsJsonObject(id).get("active").getAsInt();
        JsonObject d=version(id,v);if(trial && !d.has("validated"))throw new IllegalArgumentException("先 validate_program");
        if(!trial&&!d.get("state").getAsString().equals("PUBLISHED"))throw new IllegalArgumentException("只有已发布版本可运行");
        if(runs().has(parent))return "程序任务已存在："+parent;
        if(runs().entrySet().stream().filter(e->Set.of("RUNNING","RESTORING").contains(e.getValue().getAsJsonObject().get("state").getAsString())).count()>=8)throw new IllegalArgumentException("程序队列已有8个任务，等待资源");
        JsonObject run=new JsonObject();run.addProperty("program",id);run.addProperty("version",v);run.addProperty("actor",role.id);run.addProperty("state","RUNNING");run.addProperty("trial",trial);run.addProperty("preview",trial);run.addProperty("case_id",AgentReply.string(a,"case_id",""));run.addProperty("steps_used",0);
        JsonObject vars=new JsonObject();JsonObject args=a.has("args")?a.getAsJsonObject("args").deepCopy():new JsonObject();
        if(d.getAsJsonObject("body").has("parameters"))for(var p:d.getAsJsonObject("body").getAsJsonArray("parameters"))if(!args.has(p.getAsString()))throw new IllegalArgumentException("缺少程序参数："+p.getAsString());
        vars.add("args",args);run.add("vars",vars);run.add("frames",new JsonArray());run.add("receipts",new JsonArray());run.add("assertions",d.getAsJsonObject("body").get("assertions").deepCopy());
        if(trial)run.add("zone",a.getAsJsonObject("zone")==null?new JsonObject():a.getAsJsonObject("zone").deepCopy());
        push(run,d.getAsJsonObject("body").getAsJsonArray("steps"));runs().add(parent,run);ledger.save();return "世界程序已排队："+parent+" "+id+"@"+v+(trial?"；先只读预演，再安全区试运行":"");
    }
    private static void push(JsonObject run,JsonArray steps){JsonObject frame=new JsonObject();frame.add("steps",steps.deepCopy());frame.addProperty("pc",0);run.getAsJsonArray("frames").add(frame);}
    void tick() {
        if(port.paused())return;
        int budget=8;
        for(String id:List.copyOf(runs().keySet())) {
            JsonObject run=runs().getAsJsonObject(id);if(!Set.of("RUNNING","RESTORING").contains(run.get("state").getAsString()))continue;
            if(budget--<=0)break;
            try{step(id,run);}catch(RuntimeException failure){finish(id,run,"FAILED",failure.getMessage());}
        }
    }
    private void step(String id,JsonObject run) {
        AgentRole role=AgentRole.parse(run.get("actor").getAsString());JsonObject vars=run.getAsJsonObject("vars");
        if(run.get("state").getAsString().equals("RESTORING")) {
            for(var value:run.getAsJsonArray("receipts")) {
                JsonObject r=value.getAsJsonObject();if(!WorldActions.string(r.getAsJsonObject("action"),"type").equals("set_blocks"))continue;
                String edit=r.get("action").getAsJsonObject().get("_operation_id").getAsString(),undo=hash("program-trial-undo:"+id+":"+edit).substring(0,8),state=port.operationState(undo);
                if(state.equals("MISSING")){port.restore(role,undo,edit);return;}
                if(!state.equals("DONE")){if(!Set.of("RUNNING","RECOVERING","PAUSED").contains(state)){run.addProperty("state","NEEDS_REVIEW");run.addProperty("summary","试运行恢复冲突，保留快照；禁止发布");ledger.save();port.finished(id,role,"FAILED","试运行恢复冲突，停止覆盖");}return;}
            }
            run.addProperty("restored",true);finish(id,run,run.get("final_state").getAsString(),run.get("summary").getAsString());return;
        }
        if(!run.has("pending")&&version(run.get("program").getAsString(),run.get("version").getAsInt()).get("state").getAsString().equals("DISABLED")){finish(id,run,"FAILED","版本已因失败停用，停止继续执行");return;}
        if(run.has("wake")){if(System.currentTimeMillis()<run.get("wake").getAsLong())return;run.remove("wake");}
        if(run.has("pending")) {
            String op=run.get("pending").getAsString(),state=port.operationState(op);
            if(state.equals("MISSING") && run.has("pending_action"))state=port.dispatch(role,op,run.getAsJsonObject("pending_action"),run.get("trial").getAsBoolean());
            if(Set.of("RUNNING","PAUSED","RECOVERING").contains(state))return;
            if(!Set.of("DONE","DUPLICATE").contains(state)){finish(id,run,"FAILED","步骤 "+op+" 状态="+state+"；禁止重放未核验操作");return;}
            JsonObject receipt=port.receipt(op);run.getAsJsonArray("receipts").add(receipt);vars.add("last",receipt);
            if(run.has("save_receipt")){vars.add(run.get("save_receipt").getAsString(),receipt);run.remove("save_receipt");}
            run.remove("pending");run.remove("pending_action");ledger.save();return;
        }
        int used=run.get("steps_used").getAsInt();if(used>=10000){finish(id,run,"FAILED","程序执行预算耗尽；保存游标供调查，禁止无限循环");return;}
        JsonArray frames=run.getAsJsonArray("frames");
        if(frames.isEmpty()) {
            if(run.get("preview").getAsBoolean()) {
                run.addProperty("preview",false);run.addProperty("preview_passed",true);run.addProperty("steps_used",0);vars.remove("last");
                push(run,version(run.get("program").getAsString(),run.get("version").getAsInt()).getAsJsonObject("body").getAsJsonArray("steps"));ledger.save();return;
            }
            for(var assertion:run.getAsJsonArray("assertions"))if(!condition(assertion.getAsJsonObject(),vars)){finish(id,run,"FAILED","程序断言失败；未证明预期结果");return;}
            finish(id,run,"DONE","程序完成；断言与保护检查通过，步骤="+used);return;
        }
        JsonObject frame=frames.get(frames.size()-1).getAsJsonObject();int pc=frame.get("pc").getAsInt();JsonArray steps=frame.getAsJsonArray("steps");
        if(pc>=steps.size()){if(frame.has("assertions")&&!run.get("preview").getAsBoolean())for(var c:frame.getAsJsonArray("assertions"))if(!condition(c.getAsJsonObject(),vars))throw new IllegalArgumentException("固定子程序断言失败");if(frame.has("restore_args"))vars.add("args",frame.get("restore_args"));frames.remove(frames.size()-1);ledger.save();return;}
        JsonObject s=steps.get(pc).getAsJsonObject();String op=s.get("op").getAsString();run.addProperty("steps_used",used+1);
        if(op.equals("foreach")) {
            if(!frame.has("loop")) {
                JsonObject loop=new JsonObject();loop.addProperty("index",0);loop.addProperty("offset",0);
                if(s.has("query")){JsonObject q=resolve(s.get("query"),vars).getAsJsonObject();q.addProperty("offset",0);JsonObject result=port.query(role,q);loop.add("items",result.getAsJsonObject("data").getAsJsonArray("items").deepCopy());loop.addProperty("next",result.get("next").getAsInt());}
                else{loop.add("items",resolve(s.get("items"),vars));loop.addProperty("next",-1);}
                frame.add("loop",loop);
            }
            JsonObject loop=frame.getAsJsonObject("loop");JsonArray items=loop.getAsJsonArray("items");int i=loop.get("index").getAsInt();
            if(i<items.size()){vars.add(s.get("as").getAsString(),items.get(i).deepCopy());loop.addProperty("index",i+1);push(run,s.getAsJsonArray("steps"));}
            else if(loop.get("next").getAsInt()>=0){int next=loop.get("next").getAsInt();if(next<=loop.get("offset").getAsInt())throw new IllegalArgumentException("分页游标没有前进");JsonObject q=resolve(s.get("query"),vars).getAsJsonObject();q.addProperty("offset",next);JsonObject result=port.query(role,q);loop.add("items",result.getAsJsonObject("data").getAsJsonArray("items"));loop.addProperty("index",0);loop.addProperty("offset",next);loop.addProperty("next",result.get("next").getAsInt());}
            else{frame.remove("loop");frame.addProperty("pc",pc+1);}ledger.save();return;
        }
        frame.addProperty("pc",pc+1);
        switch(op) {
            case "query" -> vars.add(s.get("save").getAsString(),port.query(role,resolve(s.get("query"),vars).getAsJsonObject()));
            case "if" -> {String branch=condition(s.getAsJsonObject("condition"),vars)?"then":"else";if(s.has(branch))push(run,s.getAsJsonArray(branch));}
            case "wait" -> run.addProperty("wake",System.currentTimeMillis()+s.get("ticks").getAsLong()*50);
            case "call" -> {JsonObject child=version(s.get("program").getAsString(),s.get("version").getAsInt());if(!Set.of("PUBLISHED","RETIRED").contains(child.get("state").getAsString()))throw new IllegalArgumentException("固定子版本已停用");JsonElement previous=vars.get("args").deepCopy();vars.add("args",s.has("args")?resolve(s.get("args"),vars):new JsonObject());push(run,child.getAsJsonObject("body").getAsJsonArray("steps"));JsonObject called=frames.get(frames.size()-1).getAsJsonObject();called.add("restore_args",previous);called.add("assertions",child.getAsJsonObject("body").get("assertions").deepCopy());}
            case "return" -> {run.add("result",resolve(s.get("value"),vars));frame.addProperty("pc",steps.size());}
            case "action" -> {
                JsonObject a=resolve(s.get("action"),vars).getAsJsonObject();a.addProperty("case_id",run.get("case_id").getAsString());a.addProperty("_program_run",id);
                if(run.get("trial").getAsBoolean())a.add("_trial_zone",run.get("zone"));
                if(run.get("preview").getAsBoolean()){port.preview(role,a,true);JsonObject r=new JsonObject();r.addProperty("status","PREVIEW_ONLY");r.addProperty("executed",false);r.add("action",a);vars.add("last",r);if(s.has("save"))vars.add(s.get("save").getAsString(),r);}
                else {
                    String operation=hash(id+":"+used).substring(0,8);a.addProperty("_operation_id",operation);run.addProperty("pending",operation);run.add("pending_action",a.deepCopy());if(s.has("save"))run.addProperty("save_receipt",s.get("save").getAsString());
                    ledger.save();String state=port.dispatch(role,operation,a,run.get("trial").getAsBoolean());if(!Set.of("DONE","RUNNING","PAUSED","RECOVERING","DUPLICATE").contains(state))throw new IllegalArgumentException("底层工具拒绝："+state);
                }
            }
            default -> throw new IllegalArgumentException("未知程序步骤");
        }
        ledger.save();
    }
    private void finish(String id,JsonObject run,String state,String result) {
        if(run.get("trial").getAsBoolean()&&!run.has("restored") && !run.getAsJsonArray("receipts").isEmpty()) {
            run.addProperty("state","RESTORING");run.addProperty("final_state",state);run.addProperty("summary",result);
            if(state.equals("FAILED"))version(run.get("program").getAsString(),run.get("version").getAsInt()).addProperty("state","DISABLED");ledger.save();return;
        }
        run.addProperty("state",state);run.addProperty("finished",System.currentTimeMillis());run.addProperty("summary",result);
        JsonObject d=version(run.get("program").getAsString(),run.get("version").getAsInt());
        if(state.equals("DONE")&&run.get("trial").getAsBoolean()){d.addProperty("trial_passed",true);d.addProperty("trial_run",id);}
        if(state.equals("FAILED")){d.addProperty("state","DISABLED");JsonObject p=programs().getAsJsonObject(run.get("program").getAsString());if(p.get("active").getAsInt()==run.get("version").getAsInt()){int old=0;for(var e:p.getAsJsonObject("versions").entrySet())if(e.getValue().getAsJsonObject().get("state").getAsString().equals("RETIRED"))old=Math.max(old,Integer.parseInt(e.getKey()));p.addProperty("active",old);if(old>0)version(run.get("program").getAsString(),old).addProperty("state","PUBLISHED");}}
        ledger.save();port.finished(id,AgentRole.parse(run.get("actor").getAsString()),state,result);
    }
    String publish(String id,int v) {JsonObject d=version(id,v);if(!d.has("trial_passed")||!d.get("trial_passed").getAsBoolean()||d.get("state").getAsString().equals("DISABLED"))throw new IllegalArgumentException("程序尚未通过完整试运行或已停用；请创建修正版本");validateBody(d.getAsJsonObject("body"),Set.of(id+"@"+v),0);JsonObject p=programs().getAsJsonObject(id);int old=p.get("active").getAsInt();if(old>0&&old!=v)version(id,old).addProperty("state","RETIRED");d.addProperty("state","PUBLISHED");p.addProperty("active",v);ledger.save();return "世界工具已发布："+id+"@"+v+"；旧版与失败记录保留";}
    String disable(String id,int v){JsonObject d=version(id,v);d.addProperty("state","DISABLED");if(programs().getAsJsonObject(id).get("active").getAsInt()==v)programs().getAsJsonObject(id).addProperty("active",0);for(var e:runs().entrySet()){JsonObject r=e.getValue().getAsJsonObject();if(r.get("program").getAsString().equals(id)&&r.get("version").getAsInt()==v&&r.get("state").getAsString().equals("RUNNING")){r.addProperty("state","STOPPED");r.addProperty("summary","版本停用，已发生效果保留恢复记录，未继续下一步");}}ledger.save();return "程序版本已停用；已执行步骤不能宣称自动撤销";}
    JsonObject describe(){JsonObject out=new JsonObject();out.add("programs",programs().deepCopy());out.add("runs",runs().deepCopy());return out;}
    static String hash(String text){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
