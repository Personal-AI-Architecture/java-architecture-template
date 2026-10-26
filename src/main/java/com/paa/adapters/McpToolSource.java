package com.paa.adapters;

import com.paa.gateway.Json;
import com.paa.types.ToolDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Streamable HTTP MCP translation for one configured external source. */
public final class McpToolSource {
    private final String id;
    private final URI endpoint;
    private final HttpClient client;
    private final Set<String> known = new HashSet<>();
    private String sessionId;
    private int nextId = 1;
    private boolean initialized;

    public McpToolSource(String id, String url) {
        if (id == null || id.isBlank() || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) throw new IllegalArgumentException("Invalid MCP source configuration.");
        this.id = id;
        this.endpoint = URI.create(url);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    private Map<String, Object> send(String method, Map<String, Object> params, boolean notification) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("jsonrpc", "2.0"); payload.put("method", method);
        if (!notification) { payload.put("id", nextId++); payload.put("params", params); }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-03-26")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)));
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("MCP request failed (" + response.statusCode() + ").");
            sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(sessionId);
            if (notification) return Map.of();
            String body = response.body();
            if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                body = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).trim()).findFirst().orElseThrow();
            }
            Object parsed = Json.parse(body);
            if (!(parsed instanceof Map<?, ?> message) || !(message.get("result") instanceof Map<?, ?> result)) throw new IllegalStateException("MCP response was invalid or returned an error.");
            Map<String, Object> canonical = new HashMap<>();
            result.forEach((key, value) -> canonical.put(String.valueOf(key), value));
            return canonical;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP request interrupted.", error);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("MCP request failed.", error);
        }
    }

    private void initialize() {
        if (initialized) return;
        send("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(), "clientInfo", Map.of("name", "paa-template", "version", "0.1.0")), false);
        send("notifications/initialized", Map.of(), true);
        initialized = true;
    }

    public synchronized List<ToolDefinition> discover() {
        initialize();
        known.clear();
        List<ToolDefinition> definitions = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> result = send("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor), false);
            if (result.get("tools") instanceof List<?> tools) for (Object entry : tools) {
                if (!(entry instanceof Map<?, ?> item) || !(item.get("name") instanceof String name) || name.isBlank()) continue;
                known.add(name);
                Map<String, Object> parameters = new HashMap<>();
                if (item.get("inputSchema") instanceof Map<?, ?> schema) schema.forEach((key, value) -> parameters.put(String.valueOf(key), value));
                definitions.add(new ToolDefinition(name, item.get("description") instanceof String text ? text : "", parameters, Map.of("id", id, "kind", "mcp"), "discoverable", false, List.of()));
            }
            cursor = result.get("nextCursor") instanceof String text && !text.isBlank() ? text : null;
        } while (cursor != null);
        return definitions;
    }

    public synchronized Map<String, Object> call(String name, Map<String, Object> arguments) {
        if (!known.contains(name)) throw new IllegalArgumentException("Tool was not discovered from this source.");
        return send("tools/call", Map.of("name", name, "arguments", arguments), false);
    }
}
