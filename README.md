# 天空岛体系 / SkyIslandSystem

面向 Paper 1.20.1 的非官方同人服务器维护插件。插件在 AI 断线时仍可启动、展示指标并执行本地滥用防护。五位角色由**独立的 OpenClaw 实例**承载；角色间有独立 Agent 工作区、会话和记忆。法涅斯审批四影提案，世界动作由插件校验后执行。

## 当前能力

- 管理员 `/skyisland` 游戏内面板；`/skyisland ask <phanes|ronova|naberius|istaroth|asmoday> <问题>`。部署脚本启用 OpenClaw `session-memory` hook，在会话重置时将近期对话写入各自工作区；正常连续对话使用各角色固定会话历史。
- TPS、tick 时间、在线人数、磁盘、世界区块与实体数巡查；断线时不影响 Paper 主线程。
- 受限动作：时间、天气、少量游戏规则、世界边界、安全传送、限定实体生成和移除、至多 64 个普通方块编辑。四影提案必须经法涅斯审批。复杂方块编辑另需管理员确认和近 24 小时的备份文件。
- 编辑前将结构快照与日志写入磁盘，`/skyisland undo <ID>` 可在重启后尝试恢复；若区域被玩家修改则拒绝覆盖。流体、红石及邻块物理变化无法保证精确撤销。
- 独立于 AI 的高频破坏行为阈值防护。身份可验证的 `online-mode=true` 服务器自动临时封禁 30 分钟，保留事件类型、计数、玩家 UUID、时间与到期时间；`online-mode=false` 时只取消高频事件、踢出当前连接并留证，避免冒用他人名字造成误封。`/skyisland evidence <玩家或UUID>`、`/skyisland unban <UUID>`。这是针对刷方块、TNT、刷怪蛋与命令洪泛的保护，**不检测飞行、透视或所有作弊行为**。
- 五个稳定角色 ID 供未来资源包模型映射。原版客户端资源包可提供物品模型与纹理；四影的真正动态 3D NPC 仍须另做模型资产与显示实现，当前版本不声称已支持。

## 构建

JDK 17；Windows 执行 `gradlew.bat build`，Linux 执行 `./gradlew build`。成品在 `build/libs/SkyIslandSystem-0.1.1.jar`。Windows 若从中文路径运行 Gradle 测试，测试工作进程可能找不到类，可将项目映射到 ASCII 盘符后构建：`subst S: "D:\幻时镜工作台\MC天理插件设计与制作"`，再运行 `S:\gradlew.bat -p S:\ build`。构建出的 JAR 仍在原项目目录。

## 安装

1. 确认 Minecraft EULA 已由服务器所有者接受、Paper 1.20.1 可正常运行，并备份服务器。将 JAR 放入 `plugins/` 后启动 Paper。
2. 按 [详细部署教程](SETUP_GUIDE.md)操作，或将 [部署提示词](DEPLOY_PROMPT.md)交给 OpenClaw，配合 [Windows](deploy/windows.ps1) 或 [Linux](deploy/linux.sh)脚本建立专用实例。不能把主 OpenClaw 的状态目录或凭证复制到此实例。
3. 在 `plugins/SkyIslandSystem/config.yml` 中设置 `gateway-url` 为专用实例地址。把 token 写入私有的 `plugins/SkyIslandSystem/secrets.yml`，内容为 `gateway-token: "..."`；也可用服务器进程环境变量 `SKYISLAND_OPENCLAW_TOKEN`。不要提交该文件。
4. 如需复杂编辑，把 `backup-directory` 指向已有备份服务的输出目录。插件只检查最近文件，**不创建整服备份，也不自动重启服务器**。
5. 给管理员 `skyisland.admin` 权限。执行 `/skyisland status`，逐一向五位角色提问，并检查 `plugins/SkyIslandSystem/audit.log`、`evidence.log` 和 `snapshots/`。

部署时必须实际验证端口、模型请求、五个隔离会话、审批链和权限隔离；仅复制配置不能算安装完成。详细检查见 [验收清单](DEPLOY_PROMPT.md)。

## 命令

`/skyisland` 面板；`status` 状态；`ask` 提问；`pause` / `resume` 暂停或恢复 AI 世界动作；`confirm <ID>` 确认复杂编辑；`undo <ID>` 撤销方块编辑；`evidence <玩家或UUID>` 查看证据；`unban <UUID>` 解封。全部要求 `skyisland.admin`。

## 原著与授权

角色名称、权能依据公开资料整理；[设定依据与改编边界](lore/README.md)逐项标注。五位常驻、女性法涅斯、服务器职责和人格细节均是本项目同人设定。无米哈游授权，不包含官方立绘、音频或大段台词。代码采用 [MIT](LICENSE) 许可证；此许可证只适用于本仓库原创代码与文字。
