# 交给OpenClaw的v0.7.0-beta.2部署提示词

复制下面内容给拥有目标机器权限的部署Agent，同时提供系统、Paper目录、专用账号权限、模型ID、EULA接受情况和凭证的本地配置方式。不要贴密钥。

---

请在我指定的一台Paper1.20.1服务器部署SkyIslandSystem v0.7.0-beta.2。先读README、SETUP_GUIDE、deploy脚本、五套personas/AGENTS.md与SOUL.md、公共personas/TOOLS.md。

1. 检查Java17+、当前OpenClaw支持的Node、Paper1.20.1、已由我接受的EULA、磁盘、模型凭证与现有备份/守护。缺项明确报告，不宣称已完成。面板服检查Gateway与Paper是否同一容器，127.0.0.1只指向当前容器。
2. 正常停Paper，备份世界与插件私有目录，替换旧JAR。保留身份、法令、案件、处罚、保管记录、快照、agent-jobs.json和五角色已有记忆，不混入其他项目会话。
3. 建立专用Gateway系统账号SkyIslandSvc或skyisland，使用ACL核验它无法读取原OpenClaw项目或Paper私有服务器文件。Paper和Gateway各自账号，只交换专用token；五角色不得获得主机、通用文件、网络控制或跨项目会话工具。
4. 在独立OPENCLAW_STATE_DIR/OPENCLAW_CONFIG_PATH中配置模型凭证并运行对应脚本。同步AGENTS/SOUL/TOOLS，差异旧文件先备份，MEMORY.md不覆盖。核对目标CLI/schema，validate失败就停止相关步骤，不猜旧命令。工具手册由插件每轮附带，不依赖OpenClaw自动加载TOOLS.md。
5. Gateway默认私有127.0.0.1:19789，启用chat completions、独立token、五角色固定路由。生成的启动脚本固定私有状态目录，不额外套用profile。五角色实际有效模型回复后才算联通。
6. 在目标机私下复制plugin-secrets.yml为Paper/plugins/SkyIslandSystem/secrets.yml。配置实际gateway-url、结构可识别近期备份目录backup-directory、被封禁玩家的appeal-contact。保持world-autonomous，禁止恢复full-vanilla/原始控制台权限；旧配置与整根命令授权迁移前备份。
7. 保持online-mode=false。新玩家无参数register，下一条普通聊天输入密码与重复密码；旧存档用控制台私下认领码。密码不进入斜杠命令，并检查其他插件是否记录密码聊天。管理员skyisland.admin免受处罚、没收与强制限制。离线身份不能识别换号现实玩家。
8. 按docs/TESTING-v0.7.0.md在隔离世界验收：五角色会话/记忆、热点建案/委派/审批/执行回执/复查、工具纠错、处罚到期/永封/撤销/申诉、物品恢复、分批快照与并发冲突、引导/称号/个人案件、会议持久记录。世界外命令、文件、NBT与控制方块路径全部拒绝。增加治理坏 JSON 下的身份门禁、分批任务暂停/跨重启恢复、告示牌冲突、未决归还锁定和会议中断测试。不要在正式服刷实体或破坏玩家建筑测试。
9. 关闭Gateway验证Paper和本地防护继续，AI排队；恢复后检查续办与去重。守护与自启交面板或系统服务，缺少时写未配置。备份结构检查与实际恢复演练分开。
10. 核对首次细分授权备份与capabilities.json，检查法涅斯案件/区域/期限授权和撤销；运行docs/MODEL-EVAL-v0.7.0.md至少20个真实模型案件。程序用非恒真断言、只读预演和安全区普通方块试运行再发布；条件未覆盖不能算验收。检查自愿委托目标、退出、一次性物品/声望、程序暂停重启、并发冲突、失败版停用及私有经验。不要扩展AI主机/插件写入权限；本版不安装反作弊引擎。
11. 输出通过/失败/未验证表、脱敏日志位置、启动方式和待办。分别记录插件工具、模拟网关、目标真实模型及Windows/Linux部署。文档升级或一次HTTP成功不证明模型智力或长期自治。

---

Windows先运行deploy/windows.ps1 -CreateAccount，专用账号再运行-PaperRoot <目录> -ModelId <模型>。Linux先--create-user，专用账号再传入Paper目录与模型ID。脚本不代替服主接受EULA、填凭证或启动Paper。

官方接入依据：[多Agent](https://docs.openclaw.ai/concepts/multi-agent)、[工作区](https://docs.openclaw.ai/concepts/agent-workspace)。
