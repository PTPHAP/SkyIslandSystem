<div align="center">

# ✦ 天空岛体系 · SkyIslandSystem ✦

### 面向 Paper 1.20.1 的二次元世界治理插件

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:160f2b,45:4c2675,100:a855f7&height=190&section=header&text=SKY%20ISLAND%20SYSTEM&fontColor=ffffff&fontSize=30&fontAlignY=38&desc=星海之上，五位执政守望世界&descAlignY=64&descSize=16" width="100%" />

[![Paper](https://img.shields.io/badge/Paper-1.20.1-8b5cf6?style=flat-square&logo=minecraft&logoColor=white)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-17%2B-c084fc?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Release](https://img.shields.io/github/v/release/PTPHAP/SkyIslandSystem?include_prereleases&style=flat-square&color=a78bfa)](https://github.com/PTPHAP/SkyIslandSystem/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/PTPHAP/SkyIslandSystem/build.yml?style=flat-square&label=build)](https://github.com/PTPHAP/SkyIslandSystem/actions)
[![License](https://img.shields.io/badge/license-MIT-9f7aea?style=flat-square)](LICENSE)

**开源 · 可审计 · 可断线运行 · 面向服务器维护**

</div>

> **「愿星海中的每一座世界，都有人守望。」**

天空岛体系把服务器维护设计成一个有角色、有权能、有记忆边界的世界系统。五个独立 OpenClaw Agent 负责观察与判断，插件负责权限、动作白名单、审批链、证据和回滚边界。

## 🌌 五位执政

| 角色 | ID | 负责领域 | 定位 |
| :---: | :---: | :--- | :--- |
| **天理－法涅斯** | `phanes` | 全局治理 | 制定规划、调整法令、审批四影 |
| **死之执政－若娜瓦** | `ronova` | 死亡与灾难 | 死亡事件、实体过载、灾难风险 |
| **生之执政－纳贝里士** | `naberius` | 生命与生态 | 生物、玩家生存和生成压力 |
| **时之执政－伊斯塔露** | `istaroth` | 时间与稳定 | TPS、tick 时间线、备份状态 |
| **空之执政－阿斯莫代** | `asmoday` | 空间与边界 | 维度、区块、边界、传送安全 |

每位角色都有独立工作区、固定会话标识和独立记忆。四影只能提交提案，世界动作必须经过法涅斯审批和插件校验。

## ✦ 当前功能

### 服务器监控

- TPS、平均 tick 时间、在线人数、磁盘、各世界区块和实体数量。
- 有限的玩家坐标、维度和附近可移除实体 UUID。
- 游戏内管理面板、OpenClaw 状态检查和审计记录。

### 世界治理

- 法涅斯发布神圣规划和高频防护法令。
- 四影提案审批链：提交 → 法涅斯审批 → 插件校验 → 执行或拒绝。
- 支持时间、天气、白名单游戏规则、世界边界、安全传送、白名单实体生成和移除。
- 普通方块编辑最多 64 个；复杂编辑要求管理员确认和近 24 小时备份。
- 编辑前保存快照，支持 `/skyisland undo <ID>` 尝试恢复。

### 玩家安全与稳定性

- 离线模式注册、登录、改密和旧存档一次性认领。
- 未登录玩家不能移动、破坏、放置、交互或运行其他命令。
- TNT、刷怪蛋异常可首次临时封禁。
- 放置、破坏、命令洪泛首次踢出，24 小时内重复才临封。
- 保存封禁证据、到期时间和审计记录。
- 一命赛季、死亡旁观、下一赛季恢复资格。

### 游戏内表现

- 死亡显示若娜瓦，跨维度显示阿斯莫代，低 TPS 显示伊斯塔露。
- 法涅斯公布规划、法令或赛季时广播标题和原版音效。
- 玩家接近防护阈值时收到预警。

## 🎮 怎么使用

### 安装

1. 下载[最新 Release JAR](https://github.com/PTPHAP/SkyIslandSystem/releases)。
2. 准备 Paper 1.20.1、Java 17+，并接受 Minecraft EULA。
3. 将 JAR 放入 `plugins/`，启动并正常停止一次。
4. 编辑 `plugins/SkyIslandSystem/config.yml`。
5. 将 Gateway token 写入私有的 `plugins/SkyIslandSystem/secrets.yml`。
6. 给管理员 `skyisland.admin`，执行 `/skyisland doctor` 和 `/skyisland status`。

完整部署见 [SETUP_GUIDE.md](SETUP_GUIDE.md)；交给 OpenClaw 使用 [DEPLOY_PROMPT.md](DEPLOY_PROMPT.md)。

### 离线模式注册

你的服务器保持 `online-mode=false` 时：

```text
/skyisland register
下一条普通聊天：密码 重复密码
```

旧存档玩家由控制台生成一次性认领码：

```text
skyisland claim <旧玩家名或UUID>
```

玩家随后使用 `/skyisland register`，下一条普通聊天输入 `密码 重复密码 认领码`。重进使用 `/skyisland login`，改密使用 `/skyisland passwd`。不要把密码直接写进斜杠命令，因为 Paper 可能先记录命令文本。

### 常用命令

| 命令 | 权限 | 用途 |
| :--- | :---: | :--- |
| `/skyisland` | 管理员 | 打开天理议事厅面板 |
| `/skyisland status` | 管理员 | 指标、网关、防护等级和备份状态 |
| `/skyisland doctor` | 管理员 | 检查身份、Gateway 和五位 Agent |
| `/skyisland ask <角色> <问题>` | 管理员 | 向角色提问 |
| `/skyisland laws` | 玩家 | 查看公开法令和规划 |
| `/skyisland season` | 玩家 | 查看一命赛季 |
| `/skyisland evidence <玩家或UUID>` | 管理员 | 查看防护证据 |
| `/skyisland confirm <ID>` | 管理员 | 确认复杂世界编辑 |
| `/skyisland undo <ID>` | 管理员 | 尝试恢复可恢复编辑 |
| `/skyisland unban <UUID>` | 管理员 | 解除临时封禁 |

## 🔐 安全边界

- AI 不能执行任意控制台命令、系统命令、文件操作、改权限、重启服务器或回档。
- 插件会校验世界、坐标、实体、数量和边界。
- 四影不能绕过法涅斯审批。
- 箱子、红石、流体和复杂结构不能保证精确撤销，需要整服备份。
- 离线密码绑定同名离线 UUID，换名规避是离线模式的固有限制。
- 当前不声称能检测所有飞行、透视和战斗外挂。

## 🧰 配置与构建

```yaml
gateway-url: "http://127.0.0.1:19789"
request-timeout-seconds: 210
review-interval-seconds: 600
backup-directory: ""
max-simple-blocks-per-action: 64
```

Gateway 使用 HTTP/1.1，默认等待模型回复 210 秒。OpenClaw 必须使用独立状态目录、独立 token 和五份 Agent 工作区。插件断线时仍保留本地防护。

JDK 17 构建：

```powershell
gradlew.bat build
```

```bash
./gradlew build
```

当前版本输出：`build/libs/SkyIslandSystem-0.5.1.jar`。

## 📝 更新记录

### v0.5.1 · Gateway 与治理反馈修复

- 固定 OpenClaw 使用 HTTP/1.1，修复明文 HTTP/2 h2c 导致的 405。
- 支持带 Markdown 代码围栏和前后说明文字的 JSON 回复。
- 无效回复会明确通知管理员，动作不会静默丢失。
- 世界摘要增加玩家位置、维度和附近可移除实体 UUID。
- 四影提案增加提交、批准、否决、无效审批和超时回执。
- `/skyisland status` 和面板显示离线身份防护等级与备份门槛。
- 默认模型请求等待时间调整为 210 秒。
- 增加 UTF-8 Windows 启动脚本、计划任务脚本和 Linux systemd 示例。

### v0.5.0 · 离线身份与自治案件链

- 增加离线账号注册、登录、改密和旧存档认领码。
- 一命赛季和临时封禁绑定已验证账号。
- 高频事件改为对应执政调查后交法涅斯裁决。
- OpenClaw 断线时本地防护继续工作。

以后每次发布都会在这里追加版本号、日期、功能、修复和已知限制。完整路线见 [ROADMAP.md](ROADMAP.md)。

## 🌠 后续路线

1. 玩家行为证据系统和普通方块可恢复回滚。
2. 更可靠的飞行、透视和战斗异常信号。
3. 案件状态、复查、误判处理和更细处罚分级。
4. 四影资源包模型、粒子、称谓和现身表现。
5. 正式服务器、OpenClaw、面板守护和备份服务完整验收。

## 原著与授权说明

这是非官方同人服务器项目。角色名称、权能和部分设定参考公开资料；五位常驻角色、女性法涅斯、服务器职责和人格细节属于本项目同人设定。仓库不包含米哈游官方立绘、音频或大段台词。原创代码和文字采用 [MIT License](LICENSE)。

<div align="center">

### ✧ 愿你的世界永远稳定，愿你的夜晚总有星光 ✧

</div>
