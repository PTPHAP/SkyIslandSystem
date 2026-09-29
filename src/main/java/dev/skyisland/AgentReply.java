package dev.skyisland;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

record AgentReply(String message, JsonObject action, String approvalId, String approvalHash, boolean approved) {
    static AgentReply parse(String raw) {
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            String message = string(json, "message", "");
            JsonObject action = json.has("action") && json.get("action").isJsonObject()
                ? json.getAsJsonObject("action") : null;
            JsonObject approval = json.has("approval") && json.get("approval").isJsonObject()
                ? json.getAsJsonObject("approval") : null;
            return new AgentReply(message, action,
                approval == null ? "" : string(approval, "id", ""),
                approval == null ? "" : string(approval, "hash", ""),
                approval != null && approval.has("approved") && approval.get("approved").isJsonPrimitive()
                    && approval.get("approved").getAsJsonPrimitive().isBoolean()
                    && approval.get("approved").getAsBoolean());
        } catch (RuntimeException invalid) {
            // A malformed model answer is displayable text, never an action.
            return new AgentReply(raw, null, "", "", false);
        }
    }

    static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }
}
