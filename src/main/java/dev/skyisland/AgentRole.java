package dev.skyisland;

import java.util.Locale;

enum AgentRole {
    PHANES("phanes", "天理－法涅斯", "全局治理与四影审批"),
    RONOVA("ronova", "死之执政若娜瓦", "死亡、实体过载与灾难风险"),
    NABERIUS("naberius", "生之执政纳贝里士", "生物、玩家生存与生态"),
    ISTAROTH("istaroth", "时之执政伊斯塔露", "TPS、事件时间线与备份"),
    ASMODAY("asmoday", "空之执政阿斯莫代", "维度、区块、边界与传送");

    final String id;
    final String display;
    final String domain;

    AgentRole(String id, String display, String domain) {
        this.id = id;
        this.display = display;
        this.domain = domain;
    }

    static AgentRole parse(String value) {
        for (AgentRole role : values()) {
            if (role.id.equals(value.toLowerCase(Locale.ROOT)) || role.display.equals(value)) return role;
        }
        throw new IllegalArgumentException("未知角色: " + value);
    }
}
