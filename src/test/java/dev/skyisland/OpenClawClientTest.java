package dev.skyisland;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OpenClawClientTest {
    @Test void routesEachRoleToItsOwnStableSessionAndNeverTrustsMalformedAction() throws Exception {
        Map<AgentRole, String> seen = new EnumMap<>(AgentRole.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            AgentRole role = AgentRole.parse(request.get("model").getAsString().substring("openclaw/".length()));
            seen.put(role, request.get("user").getAsString());
            String body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"message\\\":\\\"ok\\\"}\"}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenClawClient client = new OpenClawClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", 5);
            for (AgentRole role : AgentRole.values()) assertEquals("ok", client.ask(role, "hello").get(5, TimeUnit.SECONDS).message());
            assertEquals("ok", client.ask(AgentRole.RONOVA, "remember").get(5, TimeUnit.SECONDS).message());
            assertEquals(5, seen.size());
            for (AgentRole role : AgentRole.values()) assertEquals("skyisland:admin:" + role.id, seen.get(role));
            assertNull(AgentReply.parse("{bad").action());
            assertFalse(AgentReply.parse("{\"approval\":{\"id\":\"a\",\"hash\":\"b\",\"approved\":\"true\"}}").approved());
            assertTrue(AgentReply.parse("{\"approval\":{\"id\":\"a\",\"hash\":\"b\",\"approved\":true}}").approved());
            assertThrows(IllegalArgumentException.class, () -> new OpenClawClient("http://example.com:19789", "token", 5));
        } finally { server.stop(0); }
    }

    @Test void reportsActionableGatewayFailureWithoutEchoingResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "private provider error detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OpenClawClient client = new OpenClawClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", 5);
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> client.ask(AgentRole.PHANES, "hello").get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("Gateway token"));
            assertFalse(failure.getCause().getMessage().contains("private provider"));
        } finally { server.stop(0); }
    }

    @Test void diagnosesMissingAgentsWithoutSendingModelRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"openclaw/phanes\"},{\"id\":\"openclaw/ronova\"}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OpenClawClient client = new OpenClawClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", 5);
            assertEquals(java.util.List.of("naberius", "istaroth", "asmoday"),
                client.missingAgents().get(5, TimeUnit.SECONDS));
        } finally { server.stop(0); }
    }
}
