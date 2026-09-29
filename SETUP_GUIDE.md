# 天空岛体系部署教程

此教程面向**一台** Paper 1.20.1 服务器。源码仓库：[PTPHAP/SkyIslandSystem](https://github.com/PTPHAP/SkyIslandSystem)；JAR 从 [Releases](https://github.com/PTPHAP/SkyIslandSystem/releases) 下载。部署时不要把模型密钥、Gateway token、`secrets.yml` 发进聊天。若希望让 OpenClaw 代为执行，把 [DEPLOY_PROMPT.md](DEPLOY_PROMPT.md) 整段交给它，并向它提供目标机器的 Paper 路径、操作权限和模型连接方式。

## 1. 准备信息

- 服务器所有者已经接受 Minecraft EULA；`eula.txt` 中为 `eula=true`。确认 Paper 是 1.20.1，并由管理员备份世界。
- 目标机器上有 Java 17+、Node 24.16+ 或 26.1+、至少 2 GiB 可用空间。当前开发机的 Node 22 **不能**运行新版 OpenClaw；对接时要先升级或给专用账号安装受支持的 Node。
- 准备模型的 `提供商/模型ID` 和该提供商的凭证。凭证只配置在专用 OpenClaw 账号中。插件只持有独立 Gateway token。
- 明确整服备份目录和服务器重启方式。插件既不做整服备份也不接管重启；如果没有现成机制，报告缺项，复杂方块编辑保持不可确认。
- 生产服若使用 `online-mode=false`，插件只踢出高频破坏连接并保留证据，不按可冒用的玩家身份自动临时封禁，也不启用一命赛季。`online-mode=true` 时 TNT、刷怪蛋超限可首次临封；方块与命令超限首次踢出，24 小时内同类行为再次超限才临封。默认封禁 30 分钟；法涅斯可在法令允许的 1-1440 分钟范围内调整。

## 2. 构建与安装 Paper 插件

下载 Release JAR，放入 Paper 的 `plugins/`，启动一次服务器，生成 `plugins/SkyIslandSystem/config.yml`，然后正常停止。管理员权限节点为 `skyisland.admin`；OP 默认拥有。插件即使没有配置 OpenClaw 也能启动、显示指标和运行本地防护。

从旧版升级时先备份 `plugins/SkyIslandSystem/`，停服后移走旧版 JAR，避免两个版本同时加载。升级后重新运行部署脚本，将新版五份 `AGENTS.md` 同步到专用 OpenClaw 工作区；脚本不会删除各角色的 `MEMORY.md`。若旧法令的高频阈值过低，插件会将原文件保存为 `laws.pre-v0.3.0.properties` 并恢复安全默认值；启动日志会提醒管理员。

如需自己构建：`git clone https://github.com/PTPHAP/SkyIslandSystem.git`，进入目录，在 Windows 执行 `gradlew.bat build`，Linux 执行 `./gradlew build`；JAR 在 `build/libs/`。Windows 中文路径上 Gradle 测试类加载失败时，可映射 ASCII 盘符，见 [README](README.md)。

## 3. 专用 OpenClaw 账号

**Windows**：管理员运行 `powershell -File deploy/windows.ps1 -CreateAccount`，在本机交互式输入新账号密码。登录新建的 `SkyIslandSvc` 账号，安装受支持的 Node，再用官方 npm 包安装 `openclaw`。切勿沿用现有账号的 `~/.openclaw` 目录。

**Linux**：管理员运行 `sudo ./deploy/linux.sh --create-user`；切换到 `skyisland`，安装受支持的 Node 和 `openclaw`。不要把旧实例的状态目录挂载或复制给这个账号。

先运行下一节的部署脚本生成专用状态目录。模型凭证随后只在该目录对应的 OpenClaw 配置中设置；不要使用 `--profile skyisland`，它会选中另一个状态目录。不要接入旧项目的频道、会话或共享工作区。

## 4. 生成五角色实例配置

**Windows**：在专用账号下运行：

```powershell
powershell -File deploy/windows.ps1 -PaperRoot 'D:\1.20.1paper' -ModelId '提供商/模型ID'
```

**Linux**：在专用账号下运行：

```bash
./deploy/linux.sh /srv/paper-1.20.1 '提供商/模型ID'
```

脚本使用私有状态目录 `~/.openclaw-skyisland`、端口 `127.0.0.1:19789`，生成五个独立 Agent 工作区与会话目录，并启用会话记忆 hook。它设置最小工具权限、关闭跨 Agent 会话工具，校验五个 Agent 的名称与隔离配置。脚本末尾打印“配置已生成”只代表**本地配置检查**，不代表模型请求或 Paper 联通已经成功。

在专用账号中为模型配置凭证，必须指向脚本生成的同一个状态目录。Windows PowerShell：

```powershell
$env:OPENCLAW_STATE_DIR = Join-Path $env:USERPROFILE '.openclaw-skyisland'
$env:OPENCLAW_CONFIG_PATH = Join-Path $env:OPENCLAW_STATE_DIR 'openclaw.json'
openclaw configure
openclaw config validate
```

Linux：

```bash
export OPENCLAW_STATE_DIR="$HOME/.openclaw-skyisland"
export OPENCLAW_CONFIG_PATH="$OPENCLAW_STATE_DIR/openclaw.json"
openclaw configure
openclaw config validate
```

如果脚本停在 Java、Node、Paper、EULA、模型、权限或配置检查处，按错误提示补齐后重跑。不要在检查失败时绕过工具权限或把已有 OpenClaw 凭证复制到 Paper 插件目录。

## 5. 启动 Gateway 与连接 Paper

在专用账号下运行脚本生成的 `~/.openclaw-skyisland/start-skyisland-gateway.ps1`（Windows）或 `~/.openclaw-skyisland/start-skyisland-gateway.sh`（Linux）。启动脚本会固定状态目录与配置路径，再启动 Gateway；确认本机 `127.0.0.1:19789` 可访问。生产部署应由管理员为该账号设置常驻服务或计划任务并调用这个启动脚本，确保重启后仍用同一状态目录、端口和凭证；不要让 Paper 插件负责拉起 Gateway。

脚本在私有状态目录生成 `plugin-secrets.yml`。由 Paper 管理员**在目标机器本地**复制到 `plugins/SkyIslandSystem/secrets.yml`，限制文件读取权限；不要复制 `openclaw.json` 或模型密钥。`plugins/SkyIslandSystem/config.yml` 中 `gateway-url` 保持默认的 `http://127.0.0.1:19789`，如需复杂编辑再将 `backup-directory` 指向已有备份目录。重启 Paper。

## 6. 真实验收

1. 在上节设置 `OPENCLAW_STATE_DIR` 与 `OPENCLAW_CONFIG_PATH` 的专用账号终端中，`openclaw config validate` 与 `openclaw agents list` 均成功，且仅列出五位角色。用专用 token 请求 `/v1/models`，再向每个 `openclaw/<id>` 发一次实际模型请求；只有 HTTP 成功和有效内容才算已联通。
2. 在 Paper 控制台运行 `skyisland doctor`，确认五角色均列出；再运行 `skyisland status`，分别运行 `skyisland ask phanes ...`、`ronova`、`naberius`、`istaroth`、`asmoday`。`doctor` 只检查列表，实际模型响应后 `OpenClaw` 才会显示“已响应”。使用不同随机短语追问，验证各角色只记住自己的短语。
3. 普通玩家可以使用 `/skyisland laws` 查看公开规划，但不能打开 `/skyisland` 管理面板；在测试服死亡并复活后应看到若娜瓦标题与文字、听到原版音效。法涅斯公布规划时在线玩家应看到标题与音效。管理员可查看指标、角色状态、提案 ID、最近操作记录。四影的动作必须在审计日志里先有提案和 `approver=phanes` 审批，再执行；无审批和错误 hash 不执行。
4. 让法涅斯在测试服公布 `declare_plan`，并在没有真实防护事件时尝试 `set_law` 的 `emergency:true`：后者应拒绝。普通 `set_law` 应公告并在约 5 分钟后生效。四影提出越界动作应留痕，连续越界后暂停提案；重启后纪律状态仍在。
5. 在**测试世界**编辑一个普通方块，记录撤销 ID；重启 Paper 后执行 `skyisland undo <ID>` 并核验方块恢复。箱子、红石、流体邻域的编辑应要求管理员确认；没有最近备份文件时确认被拒。方块随后被玩家修改时，撤销应拒绝覆盖。
6. 在受控测试账号上验证高频防护：TNT、刷怪蛋超限首次可临封；方块或命令超限首次只踢出，再次同类超限才临封。核验证据、到期时间与管理员解封。身份不可验证的离线模式只验证踢出与留证。关闭 Gateway 后，Paper 仍须正常运行且 `ask` 报请求失败。
7. 用专用账号尝试读取旧 OpenClaw 状态目录，应被操作系统拒绝；检查本实例不能访问旧项目会话。确认现有备份与重启服务仍有效。
8. 在 `online-mode=true` 的测试服让法涅斯提出 `schedule_season`，确认至少提前 24 小时公告；正式验收需等赛季开始后验证死亡旁观、重启保持资格、下赛季恢复。不可为了缩短测试而在正式世界直接编辑 `one-life.properties`。`online-mode=false` 时 `/skyisland season` 应显示规则暂停。

审计与证据位于 `plugins/SkyIslandSystem/audit.log`、`guard-evidence.log`、`snapshots/`。出现“未验证”或“请求失败”时，分别检查 Gateway 是否运行、模型凭证、端口、五个 Agent 的配置和私有 token；**不要把这些文件或凭证贴进聊天**。
