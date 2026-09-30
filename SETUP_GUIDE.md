# 天空岛体系部署教程

此教程面向**一台** Paper 1.20.1 服务器。源码仓库：[PTPHAP/SkyIslandSystem](https://github.com/PTPHAP/SkyIslandSystem)；JAR 从 [Releases](https://github.com/PTPHAP/SkyIslandSystem/releases) 下载。部署时不要把模型密钥、Gateway token、`secrets.yml` 发进聊天。若希望让 OpenClaw 代为执行，把 [DEPLOY_PROMPT.md](DEPLOY_PROMPT.md) 整段交给它，并向它提供目标机器的 Paper 路径、操作权限和模型连接方式。

## 1. 准备信息

- 服务器所有者已经接受 Minecraft EULA；`eula.txt` 中为 `eula=true`。确认 Paper 是 1.20.1，并由管理员备份世界。
- 目标机器上有 Java 17+、Node 24.16+ 或 26.1+、至少 2 GiB 可用空间。当前开发机的 Node 22 **不能**运行新版 OpenClaw；对接时要先升级或给专用账号安装受支持的 Node。
- 准备模型的 `提供商/模型ID` 和该提供商的凭证。凭证只配置在专用 OpenClaw 账号中。插件只持有独立 Gateway token。
- 明确整服备份目录和服务器重启方式。插件既不做整服备份也不接管重启；如果没有现成机制，报告缺项，复杂方块编辑保持不可确认。
- `online-mode=false` 时插件会要求玩家注册/登录；旧存档由服主在控制台一次性认领。登录后该账号可使用一命赛季和自动临封。此密码门禁不能阻止玩家另建名字，也不能约束其他插件在登录前执行的动作。`online-mode=true` 可直接使用正版账号身份。TNT、刷怪蛋超限可首次临封；方块与命令超限首次踢出，24 小时内同类行为再次超限才临封。

## 2. 构建与安装 Paper 插件

下载 Release JAR，或下载部署包 ZIP 并取出其中的 JAR，放入 Paper 的 `plugins/`，启动一次服务器，生成 `plugins/SkyIslandSystem/config.yml`，然后正常停止。部署资料还包含五位角色各自的 `AGENTS.md` 操作协议、`SOUL.md` 人格文件和 [原著依据表](lore/README.md)。管理员权限节点为 `skyisland.admin`；OP 默认拥有。插件即使没有配置 OpenClaw 也能启动、显示指标和运行本地防护。

从旧版升级时先备份 `plugins/SkyIslandSystem/`，停服后移走旧版 JAR，避免两个版本同时加载。v0.7.0-beta.2 同时更新 Paper JAR 与五套 `AGENTS.md`；重新运行部署脚本会把 `AGENTS.md`、`SOUL.md` 同步到专用 OpenClaw 工作区。已有文件若与新版不同，会先留下带时间戳的 `.bak`；各角色 `MEMORY.md` 保持原样。重启 Gateway 后分别提问确认新文档已加载。旧四影权能记录首次迁移时会保存 `shadow-discipline.pre-v0.6.0.properties`，并移除伊斯塔露的默认天气权能。若旧法令阈值过低，插件仍会备份并恢复安全默认值。

**离线服首次启用 v0.5.0**：新玩家入服后输入无参数命令 `/skyisland register`，再按提示在**下一条普通聊天**输入 `密码 重复密码`。有旧存档的玩家须由服主在面板的**服务器控制台**执行 `skyisland claim <原玩家名或UUID>`，核对 UUID 与旧存档一致，把控制台显示的一次性认领码私下交给该玩家。玩家随后输入 `/skyisland register`，下一条普通聊天输入 `密码 重复密码 认领码`；重进输入 `/skyisland login`，下一条普通聊天输入密码。已登录后改密用 `/skyisland passwd`，下一条普通聊天输入 `旧密码 新密码 重复新密码`。**绝不要把密码写进斜杠命令**，Paper 会在插件处理之前记录命令。密码须为 12 至 64 字，并且与其他网站和服务器的密码不同。插件会取消并遮盖密码聊天事件，但其他插件仍可能读取聊天事件，须检查其日志行为。切勿把密码或认领码发给 OpenClaw。将 `plugins/SkyIslandSystem/identities.properties` 与世界存档一起备份；身份文件损坏时插件会拒绝离线玩家登录，不会自动清空账号。

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

脚本使用私有状态目录 `~/.openclaw-skyisland`、端口 `127.0.0.1:19789`，生成五个独立 Agent 工作区与会话目录，并启用会话记忆 hook。每个工作区分别加载 `AGENTS.md` 的当前动作协议和 `SOUL.md` 的人格、语气与判断习惯；脚本设置最小工具权限、关闭跨 Agent 会话工具，校验五个 Agent 的名称与隔离配置。脚本末尾打印“配置已生成”只代表**本地配置检查**，不代表模型请求或 Paper 联通已经成功。

若已有隔离实例使用自定义状态目录，先在该实例下查看 `openclaw config get agents.entries --json`，核对五个角色的实际 `workspace`。把对应角色的 `AGENTS.md`、`SOUL.md` 备份后复制到该工作区，不要按本教程的默认路径猜测，也不要覆盖 `MEMORY.md`。重启该实例的 Gateway，再执行下方人格验收用例：[五角色同案测试](personas/SMOKE_TEST.md)。

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

Windows 本地 Paper 可把 `deploy/start-paper-windows.bat` 复制到 Paper 根目录并命名 `start-server.bat`。它固定 UTF-8 输出；世界锁错误时给出日志和进程检查提示，不会盲目重试占用中的世界。需要开机自启时，管理员可运行 `powershell -File deploy/install-paper-task.ps1 -PaperRoot 'D:\1.20.1paper' -ServiceUser '<专用Paper账号>'`，交互输入该账号密码，随后用 `Start-ScheduledTask -TaskName SkyIslandPaper` 测试。任务仅在异常退出后重试三次；正常停服不会触发重启。Linux 可按 `deploy/paper.service.example` 修改 `User`、`WorkingDirectory` 与 JAR 路径后交给 systemd。面板服应使用面板自身的守护和自启动设置，不能运行这些主机级脚本时明确标为未配置。

插件对专用 Gateway 使用 HTTP/1.1。新安装默认等待模型回复 210 秒；已有 `config.yml` 若仍写 25 秒，本版运行时至少使用 180 秒并在日志提醒。请按模型实际冷启动时间调整 `request-timeout-seconds`，重启 Paper 生效。若中文日志乱码，先用 UTF-8 启动脚本测试新的 `logs/latest.log`；旧日志和面板自行转码的控制台输出不会被插件追溯修复。

脚本在私有状态目录生成 `plugin-secrets.yml`。由 Paper 管理员**在目标机器本地**复制到 `plugins/SkyIslandSystem/secrets.yml`，限制文件读取权限；不要复制 `openclaw.json` 或模型密钥。`plugins/SkyIslandSystem/config.yml` 中 `gateway-url` 保持默认的 `http://127.0.0.1:19789`。复杂编辑需要把 `backup-directory` 指向备份目录：其中应有近 24 小时生成、含 `level.dat` 和 `region/*.mca` 的世界 ZIP 或世界目录；任意普通文件不会通过检查。这只检查结构，不能代替实际恢复演练。重启 Paper。

`command-mode` 固定为 `world-autonomous`。旧值 controlled/full-vanilla 会先保存配置备份再迁移；旧整根命令授权文件禁用并备份。法涅斯自主使用已实现世界工具，游戏命令文本会解析成这些工具，没有控制台权限。配置 `appeal-contact` 为你希望被封禁玩家使用的外部联系链接；为空时通知明确说明未配置。

升级时正常停止Paper，备份插件私有目录和世界，删除旧SkyIslandSystem JAR后放入新版，保留现有身份、案件、快照和五角色记忆。不要把状态复制给别的项目。部署脚本同步 AGENTS/SOUL/TOOLS，内容不同的旧文件留下时间戳备份，MEMORY.md 不覆盖。工具手册随插件每轮请求附带，不能依赖某个OpenClaw版本是否自动加载TOOLS.md。

## 6. 真实验收

按 [v0.7.0测试教程](docs/TESTING-v0.7.0.md) 分别检查安装、实际模型联通、世界自治、处罚复核、故障恢复和玩家体验。`doctor` 仅检查角色列表；五位真实应答、人格差异、固定会话记忆隔离都必须另测。

本地已验证和目标环境未验证项见 [验收记录](docs/ACCEPTANCE-v0.7.0.md)。模拟网关测试不证明目标模型已经会主动治理，也不证明部署脚本已在目标Windows/Linux账号完整安装。

私有证据位于 plugins/SkyIslandSystem/audit.log、guard-evidence.log、governance/、agent-jobs.json 和 snapshots/。排查请求失败检查目标Gateway、模型凭证、端口、五角色配置和私有token，不公开凭证或完整玩家档案。


## v0.6.1 升级注意

正常停服后备份世界和 `plugins/SkyIslandSystem/`，再替换 JAR。保留身份、治理、队列、快照和记忆。Windows 入口支持自带 PowerShell 5.1；本地完整配置生成使用模拟主机工具验证，真实账号、ACL、模型和服务守护仍需在目标机器检查。

`pause` 会写入 `ai-paused` 并在下一批前停止世界编辑、撤销和实体移除。重启后保持暂停；`resume` 重新核对并续办 AI 暂停编辑/撤销。管理员手动 `undo` 若暂停，需要恢复后再次提交同一子快照 ID。实体任务重启后重新巡查，不承诺保留内存中的扫描游标。

若治理档案坏损，密码门禁保留，普通玩家认证后仍被拒绝进入；已认证管理员和控制台用 `doctor` 查看原因。恢复可靠备份并正常重启，不删除档案绕过处罚。旧版包含方块实体的复杂快照缺少完整冲突指纹，拒绝自治撤销；请核对外部备份。普通旧快照仍可按原有条件恢复。

## v0.7.0-beta.2 升级注意

同时更新JAR与五角色文档，不覆盖MEMORY与私有token。首次授权迁移保存旧shadow-discipline.properties的pre-v070时间戳备份，生成capabilities.json；原自定义差异按具体操作迁移，默认职责以新版目录为准。身份、案件、处罚、程序、活动、经验、快照和队列仍属于本项目私有状态。

玩家认证后可用tasks/task/join/leave参加自愿委托；管理员用capabilities/tools/experience和面板查看。程序安全试运行的变化动作当前仅支持普通方块，活动临时变化仅使用快照方块。不要在正式世界尝试未验收的条件分支，不把程序排队或角色意见当成功。

本版不新增反作弊引擎，不开放插件源码写入。模型通过真实结果改进程序和策略，不是自动训练模型。真实OpenClaw验收按docs/MODEL-EVAL-v0.7.0.md的20案分别记录；缺项时保持测试版结论。
