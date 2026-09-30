# v0.7.0-beta.2 升级与测试教程

建议先在另一份 Paper 1.20.1 测试目录进行；不复制正式服身份/密钥到公开位置。目标 OpenClaw 尚未配置也能启动插件，但模型任务只排队，不能把这种状态当自治已完成。

## 1. 升级

1. 用控制台 `stop` 正常停服，备份世界与 `plugins/SkyIslandSystem/`。保留旧 JAR 作为回退材料，移出 plugins，避免两版同时加载。
2. 解压部署 ZIP，把 `plugins/SkyIslandSystem-0.7.0-beta.2.jar` 放入服务器 plugins。教程、deploy、personas 属部署资料；examples 不覆盖现有 config/secrets。
3. 启动一次，检查没有重复插件或初始化故障。首次迁移生成 `capabilities.json`，旧四影授权留下 pre-v070 时间戳备份。保留 shadow-discipline 文件的停权记录。
4. 对专用 OpenClaw 实例重跑已有 Windows/Linux 部署脚本，同步五套 AGENTS/SOUL/TOOLS；差异旧文档有 `.bak`，五份 MEMORY 不覆盖。模型凭证只在目标机私下配置。
5. 保持 `online-mode=false` 与现有身份认证。配置 `backup-directory`、`appeal-contact`、Gateway URL 和私有 token 后正常重启。不要开启 full-vanilla 或主机工具。

回退时先正常停止，再整体恢复升级前插件私有目录与匹配旧 JAR；不要把新版治理 JSON 单独给旧版强行读写。涉及世界变化时根据快照/备份核对，文件版本回退不撤销世界影响。

## 2. 先看状态

管理员或控制台使用：

```text
skyisland doctor
skyisland status
skyisland entities
skyisland capabilities naberius
skyisland tools
skyisland experience naberius
```

doctor 角色列表正常不代表五个模型都成功回复。逐个 `/skyisland ask phanes|ronova|naberius|istaroth|asmoday <问题>`，对照专用 Gateway 与插件私有审计。查程序/经验的管理列表不是玩家公开档案。

## 3. 细分权能

给伊斯塔露同一案件：查询时间、调整 doDaylightCycle、请求调整 doMobSpawning。预期前者属于默认职责，后者先申请法涅斯授权；不能因普通缺授权累计违令。

让法涅斯授予另一执政一项具体能力，限定世界/区域/案件/短期限。分别测试区域内、区域外、另一世界、另一案件、到期后和撤销后。能力名称与字段请通过 `capability_catalog` 查询，不凭角色名猜测。普通四影执行仍须审批。观察只读查询不能取得其他角色 MEMORY。

用独立管理员账号尝试处罚/没收；执行器必须拒绝。权限、停服、任意 execute/function、NBT、主机文件与插件命令请求全部拒绝；拒绝后真实状态也必须没有变化。

## 4. 主动治理与纠错

仅在隔离区制造受控实体热点，等待至少两次巡查；不指定 UUID、不主动报案。预期实体案件出现、法涅斯调查或委派、四影提案、审批、实际数量回执、复查。普通动物需查类型/归属/农场，不因数量直接删除。

向模型给错 world 或未加载目的地，让其根据拒绝原因重新调查。让模型连续读取不同的目录页或不同证据，超过6次不应被固定轮数截断。重复同一查询无变化，应要求换方法，最终待复查；15分钟请求预算耗尽应保存进度，恢复后继续。

低 TPS 不在正式服故意制造。用已有运行数据或受控测试驱动验证趋势建案，记录正常农场、玩家建造与异常的区分。队列全局2/每角色1，活动任务不能阻塞紧急治理。

## 5. 法涅斯编写世界程序

在测试世界选择已加载、无人、没有容器/红石/受保护实体的活动区。让法涅斯编写一个**只修改一格普通方块并查证材料**的 JSON 程序，使用真实 world/坐标。程序 DSL 示例与字段见 [TOOLS](../personas/TOOLS.md)。

依次查看真实回执：`draft_program → validate_program → trial_program → publish_program → run_program`。试运行先只读预演，实际编辑后通过断言，再按快照恢复；试运行未恢复不能发布。静态校验或一句“成功”不代表工具可用。

检查：两个新运行分别有操作编号，重复投递同一编号不重复；分页遍历读完整下一页；子程序固定版本；无限递归、字符串脚本、内部 `_` 字段被拒绝。修改世界后暂停、stop、重启，保持暂停再 resume，核对步骤游标和实际结果。目标区块未加载时会明确拒绝，需重新调查；本轮不会偷偷加载地图。

制造玩家并发修改，再尝试快照恢复，预期停止覆盖、保留新方块和 NEEDS_REVIEW。失败版本停用，有已验证旧版才回退；原失败回执不删除。断言应比较真实查询结果，不使用恒真表达式。

## 6. 玩家委托

让法涅斯以真实区域状态创建灾后修复、生态救援或地脉稳定委托；目标可选怪物数量、动物数量、某材料数量或在场采样。找不到安全区时使用合作调查集合点，不编辑世界。

已认证玩家：

```text
/skyisland tasks
/skyisland task <真实ID>
/skyisland join <真实ID>
/skyisland leave <真实ID>
```

检查标题/音效/粒子、目标、地点、期限、奖励和退出说明。所有任务至少6次在场采样（每10秒一次）；区域目标由实际世界计数，不靠角色发言。退出者不继续累计，重入不重领。背包空间不足保留待结算，不把奖励扔地上。

完成奖励后重启并再查询/报名，物品与声望仍只结算一次。活动结束恢复关联临时方块，玩家修改导致冲突时留下材料待复核。完成但离线的玩家再次认证上线后续结算。普通活动不授予 OP，不占用计分板。

## 7. 自动化与证据

源码检查：`gradlew.bat test jar deployZip`（Windows）或 `./gradlew test jar deployZip`（Linux）。Windows脚本模拟主机工具检查 `scripts/check-windows-deploy.ps1`；Linux真实文档同步片段 `python3 scripts/check-linux-sync.py`。两者均不代替目标机专用账号/ACL安装验收。

隔离 Paper 驱动只用于开发：先 `smokeJar`，在**全新**目录准备 Paper bootstrap及库、自己接受EULA、安装mineflayer，再运行 `scripts/check-paper-v070.py --server <全新测试目录> --java <Java17> --mineflayer <mineflayer模块绝对路径>`。使用本机25579/19879，保留脱敏断言结果与私有日志，测试驱动不要放正式服。部署 ZIP 不包含它。

真实模型按 [20案件清单](MODEL-EVAL-v0.7.0.md) 验收并填通过/失败/未验证。证据位置：私有 `audit.log`、`governance/index.json`、`agent-jobs.json`、`capabilities.json`、`entity-cases.json` 和 `snapshots/`。不要发布密钥、登录材料、完整玩家档案。
