package dev.skyisland;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

record AgentReply(String message, JsonObject action, String delegateRole, String approvalId, String approvalHash, boolean approved,
                  boolean validFormat) {
    static AgentReply parse(String raw) {
        try {
            JsonObject json = JsonParser.parseString(firstObject(raw)).getAsJsonObject();
            if (!json.has("message") && !json.has("action") && !json.has("approval") && !json.has("delegate"))
                throw new IllegalArgumentException("不是角色回复对象");
            String message = string(json, "message", "");
            if (json.has("action") && !json.get("action").isJsonNull() && !json.get("action").isJsonObject())
                throw new IllegalArgumentException("action 不是对象");
            if (json.has("approval") && !json.get("approval").isJsonNull() && !json.get("approval").isJsonObject())
                throw new IllegalArgumentException("approval 不是对象");
            if (json.has("delegate") && !json.get("delegate").isJsonNull() && !json.get("delegate").isJsonObject())
                throw new IllegalArgumentException("delegate 不是对象");
            JsonObject action = json.has("action") && json.get("action").isJsonObject()
                ? json.getAsJsonObject("action") : null;
            JsonObject approval = json.has("approval") && json.get("approval").isJsonObject()
                ? json.getAsJsonObject("approval") : null;
            JsonObject delegate = json.has("delegate") && json.get("delegate").isJsonObject()
                ? json.getAsJsonObject("delegate") : null;
            if (approval != null && approval.has("approved") && (!approval.get("approved").isJsonPrimitive()
                || !approval.get("approved").getAsJsonPrimitive().isBoolean()))
                throw new IllegalArgumentException("approved 必须是布尔值");
            return new AgentReply(message, action, delegate == null ? "" : string(delegate, "role", ""),
                approval == null ? "" : string(approval, "id", ""),
                approval == null ? "" : string(approval, "hash", ""),
                approval != null && approval.has("approved") && approval.get("approved").isJsonPrimitive()
                    && approval.get("approved").getAsJsonPrimitive().isBoolean()
                    && approval.get("approved").getAsBoolean(), true);
        } catch (RuntimeException invalid) {
            // A malformed model answer is displayable text, never an action.
            return new AgentReply(raw == null ? "" : raw, null, "", "", "", false, false);
        }
    }

    private static String firstObject(String raw) {
        if (raw == null || raw.length() > 16_384) throw new IllegalArgumentException("回复过长");
        int start = raw.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("缺少 JSON 对象");
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && ch == '\\') { escaped = true; continue; }
            if (ch == '"') { quoted = !quoted; continue; }
            if (!quoted && ch == '{') depth++;
            if (!quoted && ch == '}' && --depth == 0) return raw.substring(start, i + 1);
        }
        throw new IllegalArgumentException("JSON 对象未闭合");
    }

    static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }
}
