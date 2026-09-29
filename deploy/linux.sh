#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACCOUNT=skyisland
if [[ "${1:-}" == --create-user ]]; then
  [[ "$(id -u)" == 0 ]] || { echo '请以 root 创建专用账号'; exit 1; }
  id "$ACCOUNT" >/dev/null 2>&1 || useradd --create-home --shell /bin/bash "$ACCOUNT"
  echo "账号已准备。切换到 $ACCOUNT 后运行: $ROOT/deploy/linux.sh <Paper目录> <模型ID>"
  exit 0
fi
[[ "$(id -un)" == "$ACCOUNT" ]] || { echo "请以独立账号 $ACCOUNT 运行；先由管理员执行 $0 --create-user"; exit 1; }
[[ $# == 2 ]] || { echo "用法: $0 <Paper目录> <提供商/模型ID>"; exit 1; }
PAPER_ROOT="$1"; MODEL="$2"
[[ -f "$PAPER_ROOT/eula.txt" && -d "$PAPER_ROOT/plugins" ]] || { echo '缺少 Paper 路径、plugins 目录或 EULA 文件'; exit 1; }
grep -q '^eula=true' "$PAPER_ROOT/eula.txt" || { echo 'EULA 未由服务器所有者接受'; exit 1; }
compgen -G "$PAPER_ROOT/plugins/SkyIslandSystem-*.jar" >/dev/null || { echo '请先将天空岛体系 Release JAR 放入 Paper/plugins'; exit 1; }
command -v python3 >/dev/null && command -v openssl >/dev/null || { echo '需要 python3 和 openssl'; exit 1; }
command -v java >/dev/null || { echo '需要 Java 17+'; exit 1; }
JAVA_MAJOR="$(java -version 2>&1 | head -n 1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ && "$JAVA_MAJOR" -ge 17 ]] || { echo 'Java 版本不受支持'; exit 1; }
[[ -f "$PAPER_ROOT/Paper-1.20.1.jar" ]] || { echo '找不到 Paper-1.20.1.jar'; exit 1; }
python3 - "$PAPER_ROOT/Paper-1.20.1.jar" <<'PY'
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    assert json.loads(jar.read('version.json'))['id'] == '1.20.1'
    assert b'io.papermc.paperclip.Main' in jar.read('META-INF/MANIFEST.MF')
PY
FREE_KIB="$(df -Pk "$PAPER_ROOT" | awk 'END {print $4}')"
[[ "$FREE_KIB" =~ ^[0-9]+$ && "$FREE_KIB" -ge 2097152 ]] || { echo 'Paper 分区可用空间不足 2 GiB'; exit 1; }
command -v node >/dev/null || { echo '需要 Node 24.16+ 或 26.1+'; exit 1; }
node -e 'const v=process.versions.node.split(".").map(Number); if (!((v[0]===24&&v[1]>=16)||(v[0]===26&&v[1]>=1)||(v[0]>26))) process.exit(1)' || { echo 'Node 版本不受支持'; exit 1; }
command -v openclaw >/dev/null || { echo '请在专用账号下安装 OpenClaw (npm install -g openclaw)，并完成模型凭证配置'; exit 1; }
STATE="$HOME/.openclaw-skyisland"
mkdir -p "$STATE"
chmod 700 "$STATE"
export OPENCLAW_STATE_DIR="$STATE"
export OPENCLAW_CONFIG_PATH="$STATE/openclaw.json"
if [[ ! -f "$OPENCLAW_CONFIG_PATH" ]]; then openclaw setup --baseline; fi
for role in phanes ronova naberius istaroth asmoday; do
  install -d -m 700 "$STATE/workspaces/$role"
  for name in AGENTS.md SOUL.md; do
    source="$ROOT/personas/$role/$name"
    target="$STATE/workspaces/$role/$name"
    if [[ -f "$target" ]] && ! cmp -s "$source" "$target"; then
      cp -p -- "$target" "$target.$(date -u +%Y%m%dT%H%M%S%N).bak"
    fi
    install -m 600 "$source" "$target"
  done
  [[ -f "$STATE/workspaces/$role/MEMORY.md" ]] || install -m 600 /dev/null "$STATE/workspaces/$role/MEMORY.md"
done
if [[ ! -f "$STATE/gateway.token" ]]; then
  umask 077
  openssl rand -hex 32 > "$STATE/gateway.token"
fi
TOKEN="$(cat "$STATE/gateway.token")"
export STATE MODEL TOKEN
python3 - <<'PY' > "$STATE/skyisland.patch.json"
import json, os
s=os.environ['STATE']; model=os.environ['MODEL']; token=os.environ['TOKEN']
roles=['phanes','ronova','naberius','istaroth','asmoday']
print(json.dumps({
 'gateway': {'mode':'local','port':19789,'bind':'loopback','auth':{'mode':'token','token':token},
             'http':{'endpoints':{'chatCompletions':{'enabled':True}}}},
 'tools': {'profile':'minimal','sessions':{'visibility':'self'},'agentToAgent':{'enabled':False},
           'fs':{'workspaceOnly':True},'deny':['exec','process','read','write','edit','apply_patch','browser','gateway','sessions_list','sessions_history','sessions_search','sessions_send','sessions_spawn']},
 'agents':{'defaults':{'model':{'primary':model}},'entries':{
   r:{'workspace':s+'/workspaces/'+r,'agentDir':s+'/agents/'+r+'/agent','identity':{'name':r},'default':r=='phanes'} for r in roles}}
},ensure_ascii=False))
PY
chmod 600 "$STATE/skyisland.patch.json"
openclaw config patch --file "$STATE/skyisland.patch.json" --replace-path agents.entries --dry-run
openclaw config patch --file "$STATE/skyisland.patch.json" --replace-path agents.entries
openclaw config validate
[[ "$(openclaw config get gateway.port)" == 19789 && "$(openclaw config get gateway.bind)" == loopback \
   && "$(openclaw config get tools.profile)" == minimal \
   && "$(openclaw config get tools.agentToAgent.enabled)" == false ]] || { echo 'Gateway 或工具隔离配置未生效'; exit 1; }
openclaw config get agents.entries --json | python3 -c 'import json,sys; assert set(json.load(sys.stdin)) == {"phanes","ronova","naberius","istaroth","asmoday"}'
openclaw hooks enable session-memory
openclaw agents list
cat > "$STATE/start-skyisland-gateway.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
STATE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export OPENCLAW_STATE_DIR="$STATE"
export OPENCLAW_CONFIG_PATH="$STATE/openclaw.json"
exec openclaw gateway run --port 19789
SH
chmod 700 "$STATE/start-skyisland-gateway.sh"
printf 'gateway-token: "%s"\n' "$TOKEN" > "$STATE/plugin-secrets.yml"
chmod 600 "$STATE/plugin-secrets.yml"
echo "配置已生成于 $STATE。由 Paper 管理员安装 JAR 并安全复制 $STATE/plugin-secrets.yml 到 Paper/plugins/SkyIslandSystem/secrets.yml。"
echo "由专用账号运行 $STATE/start-skyisland-gateway.sh，再做真实模型与隔离验收；脚本未宣称已完成安装。"
