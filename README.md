# 天空岛体系 / SkyIslandSystem

面向 Paper 1.20.1 的非官方同人服务器维护插件。插件在 AI 断线时仍可启动、展示指标并执行本地滥用防护。五位角色由**独立于现有项目的 OpenClaw 实例**承载；角色间有独立 Agent 工作区、会话和记忆。法涅斯审批四影提案，世界动作由插件校验后执行。

## 当前能力

- 管理员 `/skyisland` 游戏内面板；`/skyisland ask <phanes|ronova|naberius|istaroth|asmoday> <问题>`。部署脚本启用 OpenClaw `session-memory` hook，在会话重置时将近期对话写入各自工作区；正常连续对话使用各角色固定会话历史。
- 法涅斯可独立公布持久化的神圣规划、调整五类高频防护法令，以及授予或收回四影的动作提案权能。普通法令公告 5 分钟后生效；只有近期真实防护事件允许立即生效。玩家可用 `/skyisland laws` 查看公开规划。四影越界提案会被拒绝并记录警告或限时停权，法涅斯可解除停权；四影仍保有独立对话与记忆。
- TPS、tick 时间、在线人数、磁盘、世界区块与实体数巡查；受限展示在线玩家坐标及附近可移除实体 UUID，供世界动作判断。断线时不影响 Paper 主线程。
- 受限动作：时间、天气、少量游戏规则、世界边界、安全传送、限定实体生成和移除、至多 64 个普通方块编辑。四影提案必须经法涅斯审批。复杂方块编辑另需管理员确认和近 24 小时的备份文件。
- 编辑前将结构快照与日志写入磁盘，`/skyisland undo <ID>` 可在重启后尝试恢复；若区域被玩家修改则拒绝覆盖。流体、红石及邻块物理变化无法保证精确撤销。
- 独立于 AI 的高频破坏行为阈值防护。已登录账号的 TNT 和刷怪蛋异常可首次临封；建造、挖掘、命令高频先踢出，同类行为 24 小时内重复才临封。默认封禁 30 分钟，单次最长 24 小时。离线服在玩家完成天空岛登录后按该账号临封；未登录者无法游戏。`/skyisland evidence <玩家或UUID>`、`/skyisland unban <UUID>`。这是确定性高频行为防护，**不检测飞行、透视或所有作弊行为**。
- 五个稳定角色 ID 供未来资源包模型映射。原版客户端资源包可提供物品模型与纹理；四影的真正动态 3D NPC 仍须另做模型资产与显示实现，当前版本不声称已支持。
- 离线服内置注册/登录；旧玩家存档须由控制台签发一次性认领码，防止冒名抢先注册。密码以加盐 PBKDF2 哈希保存，未登录时限制游戏操作。法涅斯可提前 24 至 168 小时公告一命赛季；已验证玩家全维度共享一条命，死亡后旁观，资格跨重启保存，下一赛季恢复。
- 高频事件先由对应执政调查，再交法涅斯裁决；提案提交、否决、超时和动作结果会通知在线管理员并写入审计日志。插件五分钟后复查同类事件。立即生效的法令须有**同类**近期真实事件。AI 断线后本地防护仍运行。
- 玩家死亡、跨维度及持续低 TPS 时分别显示若娜瓦、阿斯莫代及伊斯塔露的称谓与原版音效；法涅斯公布规划、法令或新赛季时也会公告。玩家的高频操作在达到阈值的四分之三时收到一次预警。
- 管理员 `/skyisland doctor` 检查身份模式、Gateway 凭证及五位 Agent 是否列在 `/v1/models`；实际回复和记忆隔离仍须分别提问验证。

## 构建

JDK 17；Windows 执行 `gradlew.bat build`，Linux 执行 `./gradlew build`。成品在 `build/libs/SkyIslandSystem-0.5.1.jar`。Windows 若从中文路径运行 Gradle 测试，测试工作进程可能找不到类，可将项目映射到 ASCII 盘符后构建：`subst S: "D:\幻时镜工作台\MC天理插件设计与制作"`，再运行 `S:\gradlew.bat -p S:\ build`。构建出的 JAR 仍在原项目目录。

## 安装

1. 确认 Minecraft EULA 已由服务器所有者接受、Paper 1.20.1 可正常运行，并备份服务器。将 JAR 放入 `plugins/` 后启动 Paper。
2. 按 [详细部署教程](SETUP_GUIDE.md)操作，或将 [部署提示词](DEPLOY_PROMPT.md)交给 OpenClaw，配合 [Windows](deploy/windows.ps1) 或 [Linux](deploy/linux.sh)脚本建立专用实例。不能把主 OpenClaw 的状态目录或凭证复制到此实例。
3. 在 `plugins/SkyIslandSystem/config.yml` 中设置 `gateway-url` 为专用实例地址。把 token 写入私有的 `plugins/SkyIslandSystem/secrets.yml`，内容为 `gateway-token: "..."`；也可用服务器进程环境变量 `SKYISLAND_OPENCLAW_TOKEN`。不要提交该文件。
4. 如需复杂编辑，把 `backup-directory` 指向已有备份服务的输出目录。插件只检查最近文件，**不创建整服备份，也不自动重启服务器**。
5. 离线服新玩家输入无参数命令 `/skyisland register`，然后在**下一条普通聊天**输入 `密码 重复密码`；有旧存档的玩家先由服主在**控制台**执行 `skyisland claim <原玩家名或UUID>`，私下交付认领码，再用 `/skyisland register` 和下一条聊天 `密码 重复密码 认领码` 注册。重进后输入 `/skyisland login`，下一条普通聊天输入密码。改密用 `/skyisland passwd`，下一条聊天输入 `旧密码 新密码 重复新密码`。**绝不要把密码写进斜杠命令**：Paper 会先记录命令，再交给插件。密码应为本站独有的 12 至 64 字；其他插件仍可能读取聊天事件，部署前应检查其日志行为。务必备份 `plugins/SkyIslandSystem/identities.properties`，不要公开认领码。
6. 给管理员 `skyisland.admin` 权限。执行 `/skyisland doctor`、`/skyisland status`，逐一向五位角色提问，并检查 `plugins/SkyIslandSystem/audit.log`、`guard-evidence.log`、`laws.properties`、`one-life.properties` 和 `snapshots/`。升级已有 OpenClaw 实例时，重新运行部署脚本以同步五份人格文件；不要删除角色的独立记忆。

部署时必须实际验证端口、模型请求、五个隔离会话、审批链和权限隔离；仅复制配置不能算安装完成。详细检查见 [验收清单](DEPLOY_PROMPT.md)。

## 命令

离线服 `/skyisland register`、`/skyisland login` 用于玩家身份验证，`/skyisland passwd` 用于改密；`skyisland claim` 仅控制台可用。登录后 `/skyisland laws` 与 `/skyisland season` 向所有玩家开放；`/skyisland` 面板、`status`、`doctor`、`ask`、`pause` / `resume`、`confirm <ID>`、`undo <ID>`、`evidence <玩家或UUID>`、`unban <UUID>` 要求 `skyisland.admin`。

离线密码只保护**同名账号**，无法阻止玩家另建新名字绕过账号封禁；与其他插件共享离线玩家权限时也需单独检查其登录前行为。当前尚无玩家方块回滚、飞行/透视检测、玩家自由对话、角色实体或专属音效。当前提示仅使用 Minecraft 原版音效。后续阶段见 [路线图](ROADMAP.md)。

## 原著与授权

角色名称、权能依据公开资料整理；[设定依据与改编边界](lore/README.md)逐项标注。五位常驻、女性法涅斯、服务器职责和人格细节均是本项目同人设定。无米哈游授权，不包含官方立绘、音频或大段台词。代码采用 [MIT](LICENSE) 许可证；此许可证只适用于本仓库原创代码与文字。
