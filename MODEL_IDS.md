# 未来四影资源包模型接入

稳定角色 ID：`ronova`、`naberius`、`istaroth`、`asmoday`；法涅斯为 `phanes`。未来资源包目录建议 `assets/skyisland/models/item/<id>.json` 与 `assets/skyisland/textures/entity/<id>.png`。每个模型资产应独立制作并确认授权。

当前插件只保留角色 ID 与界面入口。原版 1.20.1 客户端资源包能替换物品模型和纹理，但资源包本身不能实现自由骨骼动画的角色 NPC。以后应先确定服务端实体展示方式、动画约束、资源包下发与客户端验证，再开发 3D 展示；不要将静态物品模型冒充动态角色。
