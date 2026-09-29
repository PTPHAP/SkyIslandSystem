package dev.skyisland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class OpenClawClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final String token;
    private final Duration timeout;

    OpenClawClient(String gatewayUrl, String token, int timeoutSeconds) {
        this.endpoint = URI.create(gatewayUrl.replaceAll("/+$", "") + "/v1/chat/completions");
        if (!"http".equals(endpoint.getScheme()) || !("127.0.0.1".equals(endpoint.getHost())
            || "localhost".equals(endpoint.getHost()) || "::1".equals(endpoint.getHost())))
            throw new IllegalArgumentException("OpenClaw Gateway 必须使用本机回环地址");
        this.token = token;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    boolean configured() { return !token.isBlank(); }

    CompletableFuture<List<String>> missingAgents() {
        if (!configured()) return CompletableFuture.failedFuture(new IllegalStateException("OpenClaw 凭证未配置"));
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/v1/models")).timeout(timeout)
            .header("Authorization", "Bearer " + token).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException(statusError(response.statusCode()));
            try {
                JsonArray models = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data");
                List<String> missing = new ArrayList<>();
                for (AgentRole role : AgentRole.values()) {
                    boolean found = false;
                    for (int i = 0; i < models.size(); i++)
                        if (("openclaw/" + role.id).equals(models.get(i).getAsJsonObject().get("id").getAsString())) found = true;
                    if (!found) missing.add(role.id);
                }
                return missing;
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("OpenClaw 模型列表格式错误：检查专用实例日志", invalid);
            }
        });
    }

    CompletableFuture<AgentReply> ask(AgentRole role, String text) {
        if (!configured()) return CompletableFuture.failedFuture(new IllegalStateException("OpenClaw 凭证未配置"));
        JsonObject body = new JsonObject();
        body.addProperty("model", "openclaw/" + role.id);
        body.addProperty("user", "skyisland:admin:" + role.id);
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", text + "\n只返回 JSON：{\"message\":\"对管理员的话\",\"action\":null 或受限动作对象，\"approval\":null 或 {\"id\":\"...\",\"hash\":\"...\",\"approved\":true/false}}。不得输出 Markdown 代码块。");
        messages.add(message);
        body.add("messages", messages);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) throw new IllegalStateException(statusError(response.statusCode()));
                try {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    String content = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                    return AgentReply.parse(content);
                } catch (RuntimeException invalid) {
                    throw new IllegalStateException("OpenClaw 回复格式错误：检查专用实例日志", invalid);
                }
            });
    }

    private static String statusError(int code) {
        return switch (code) {
            case 401, 403 -> "OpenClaw HTTP " + code + "：检查专用 Gateway token";
            case 404 -> "OpenClaw HTTP 404：检查 Gateway 接口和角色 Agent ID";
            default -> "OpenClaw HTTP " + code + "：检查专用实例日志与模型连接";
        };
    }
}
