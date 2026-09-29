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
[[ -f "$ROOT/build/libs/SkyIslandSystem-0.1.0.jar" ]] || { echo '请先构建 JAR'; exit 1; }
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
  install -m 600 "$ROOT/personas/$role/AGENTS.md" "$STATE/workspaces/$role/AGENTS.md"
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
   r:{'workspace':s+'/workspaces/'+r,'identity':{'name':r}} for r in roles}}
},ensure_ascii=False))
PY
chmod 600 "$STATE/skyisland.patch.json"
openclaw config patch --file "$STATE/skyisland.patch.json" --dry-run
openclaw config patch --file "$STATE/skyisland.patch.json"
openclaw config validate
openclaw hooks enable session-memory
openclaw agents list
printf 'gateway-token: "%s"\n' "$TOKEN" > "$STATE/plugin-secrets.yml"
chmod 600 "$STATE/plugin-secrets.yml"
echo "配置已生成于 $STATE。由 Paper 管理员安装 JAR 并安全复制 $STATE/plugin-secrets.yml 到 Paper/plugins/SkyIslandSystem/secrets.yml。"
echo '由专用账号启动 openclaw gateway，再做真实模型与隔离验收；脚本未宣称已完成安装。'
