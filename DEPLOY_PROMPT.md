# 交给 OpenClaw 的部署提示词

将下面整段复制给拥有目标机器操作权限的部署 Agent。每次只针对一台 Paper 服务器。**先提供**：目标服务器系统和 Paper 根目录、可用的独立系统账号权限、模型提供商及凭证配置方式、服务器所有者已接受 Minecraft EULA 的证据、现有备份与重启机制。模型密钥和 Gateway token 不要贴进对话或日志。

> 请部署本仓库中的“天空岛体系” Paper 1.20.1 插件。先检查本仓库 README、`deploy/windows.ps1` 或 `deploy/linux.sh`、五份 `personas` 文件及源代码，不修改任何现有 OpenClaw 实例。每次部署只面向我指定的一台 Paper 服务器。
>
> 1. 核实目标机器的 Paper 1.20.1、Java、Node、磁盘空间、Minecraft EULA、已授权的模型凭证、可用的本地备份和重启服务。缺项就停止相关安装步骤，逐项报告待办，不得假称完成。
> 2. 创建专用操作系统账号 `SkyIslandSvc`（Windows）或 `skyisland`（Linux），确保它不能读取原有 OpenClaw 配置、状态、项目文件和会话；将专用实例状态及五个 Agent 工作区放在此账号的私有目录。插件和 Paper 进程只获得专用 Gateway token，不取得模型密钥。
> 3. 在专用账号中安装受支持的 OpenClaw，运行对应部署脚本，生成五个 Agent：`phanes`、`ronova`、`naberius`、`istaroth`、`asmoday`。每个 Agent 复制对应人格文件，使用独立工作区和记忆文件。按 `SETUP_GUIDE.md` 在脚本指定的同一状态目录配置模型凭证并使用生成的启动脚本，不使用额外 `--profile`。Gateway 仅监听 `127.0.0.1:19789`，启用 `/v1/chat/completions`，设置最小工具权限、禁止跨 Agent 会话访问。验证 `openclaw config validate`、`openclaw agents list` 与实际模型应答。
> 4. 构建并安装 JAR，私下把专用 token 配进 `plugins/SkyIslandSystem/secrets.yml`，设置 `config.yml` 的 Gateway URL 和真实备份目录。离线服先备份旧世界，再为旧玩家由**服务器控制台**逐个生成一次性认领码并私下交付；新玩家按 README 用无参数 `/skyisland register` 和下一条普通聊天注册。绝不把密码写入斜杠命令；检查其他插件会不会记录聊天事件。备份 `identities.properties`，不要在 OpenClaw 对话中传递密码或认领码。为管理员授权 `skyisland.admin`。不要让角色执行任意控制台命令、系统命令、文件操作、改权限、重启或回档。
> 5. 真实验收：Paper 启动无异常；离线服未登录玩家不能操作或运行其他命令，新玩家可注册，旧存档必须用一次性认领码注册，重进须再次登录，冒名者不知密码不能游戏；普通已登录玩家可查看 `/skyisland laws` 与 `/skyisland season` 但打不开管理面板；管理员 `doctor` 列出五位 Agent，再用五次 `ask` 验证实际模型回复和各角色独立记忆；同类高频事件先由对应执政调查，再由法涅斯裁决并在五分钟后复查，未经批准的四影提案不执行；法令变更须经过插件校验，紧急法令只能依据同类近期事件；关闭 OpenClaw 时 Paper 仍运行；已验证账号的 TNT 与刷怪蛋高频首次可临封，方块与命令高频首次踢出、24 小时内同类行为再次超限才临封，均须有证据；一命赛季须验证提前公告、死亡旁观、重启保持和新赛季恢复；普通方块编辑重启后撤销，复杂编辑经确认。先在测试世界做破坏性验收，未等到赛季正式开始时标为未验证。
> 6. Windows 与 Linux 各运行一次前提检查。核验专用账号无法读取原 OpenClaw 目录，不能跨 Agent 调用会话工具。确认备份与重启由现有服务负责；未检测到时明确写“未配置”。最后给我一张通过/失败/未验证的验收表，并附不含凭证的日志位置。绝不将“脚本执行成功”当成真实联通完成。

## 本地预检

Windows 管理员先运行 `powershell -File deploy/windows.ps1 -CreateAccount`；登录专用账号，配置模型后运行 `powershell -File deploy/windows.ps1 -PaperRoot <路径> -ModelId <提供商/模型>`。Linux 管理员先运行 `sudo ./deploy/linux.sh --create-user`；切换 `skyisland`，配置模型后运行 `./deploy/linux.sh <Paper路径> <提供商/模型>`。

脚本检查前提并生成私有配置，**不会替服务器所有者接受 EULA，不会猜测模型凭证，不会自动安装或启动 Paper，也不会把私有 token 打印到终端**。专用账号下的 OpenClaw 安装、模型认证及 Gateway 常驻服务需要目标机管理员按实际环境完成并验证。对其他机器的结果不可复用。
