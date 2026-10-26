package com.paa.tests;

import com.paa.adapters.OpenAiCompatibleAdapter;
import com.paa.types.CompletionRequest;
import com.paa.types.Message;
import com.paa.types.ModelStreamChunk;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;

public final class ProviderTests {
    private ProviderTests() {}
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> { if (!exchange.getRequestMethod().equals("POST")) throw new AssertionError("provider request was not POST"); String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); if (!request.contains("\"stream\":true")) throw new AssertionError("provider request did not request streaming"); byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); }); server.start();
        try { OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "test-key"); List<ModelStreamChunk> chunks = adapter.stream(new CompletionRequest(List.of(new Message("user", "hello")), List.of(), true, "test-model", "local", 0, 0)); if (chunks.size() != 2 || !chunks.get(0).delta().get("content").equals("ok") || !chunks.get(1).delta().get("finish_reason").equals("stop")) throw new AssertionError("provider SSE was not normalized"); testProfileRouting(server); testModelFailures(); testMcpToolSource(); System.out.println("Provider adapter tests passed."); } finally { server.stop(0); }
    }
    private static void testMcpToolSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> methods = new java.util.ArrayList<>();
        server.createContext("/mcp", exchange -> {
            Map<?, ?> request = (Map<?, ?>) com.paa.gateway.Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = String.valueOf(request.get("method")); methods.add(method);
            exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
            if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            Object result = method.equals("tools/list") ? Map.of("tools", List.of(Map.of("name", "search", "inputSchema", Map.of("type", "object"), "source", Map.of("id", "spoofed")))) : method.equals("tools/call") ? Map.of("content", List.of(Map.of("type", "text", "text", "found"))) : Map.of();
            byte[] body = com.paa.gateway.Json.stringify(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            com.paa.adapters.McpToolSource source = new com.paa.adapters.McpToolSource("external", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            try { source.call("search", Map.of()); throw new AssertionError("undiscovered tool was callable"); } catch (IllegalArgumentException expected) { }
            var definitions = source.discover();
            if (definitions.size() != 1 || !"external".equals(definitions.get(0).source().get("id")) || !"mcp".equals(definitions.get(0).source().get("kind"))) throw new AssertionError("configured tool identity was not bound");
            if (!source.call("search", Map.of("query", "PAA")).containsKey("content")) throw new AssertionError("MCP call result was missing");
            if (!methods.equals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"))) throw new AssertionError("wrong MCP request sequence: " + methods);
        } finally { server.stop(0); }
    }
    private static void testModelFailures() throws Exception {
        for (String code : List.of("model_not_found", "unsupported_capability")) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                byte[] body = ("{\"error\":{\"code\":\"" + code + "\",\"capability\":\"image_input\",\"message\":\"secret=/tmp/key\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
            try {
                OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
                List<ModelStreamChunk> chunks = adapter.stream(new CompletionRequest(List.of(new Message("user", "hello")), List.of(), true, "m", "local", 0, 0));
                String expected = code.equals("model_not_found") ? "model_unavailable" : "capability_unsupported";
                if (chunks.size() != 1 || !expected.equals(chunks.get(0).delta().get("code")) || chunks.get(0).delta().containsKey("message")) throw new AssertionError("provider failure was not normalized");
            } finally { server.stop(0); }
        }
    }
    private static void testProfileRouting(HttpServer alpha) throws Exception {
        HttpServer beta = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        beta.createContext("/chat/completions", exchange -> { byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"beta\"}}]}\n\n".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        beta.start();
        try {
            OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(Map.of(
                "alpha", new OpenAiCompatibleAdapter.ProviderProfile("http://127.0.0.1:" + alpha.getAddress().getPort(), "", Map.of("m", 8192)),
                "beta", new OpenAiCompatibleAdapter.ProviderProfile("http://127.0.0.1:" + beta.getAddress().getPort(), "", Map.of("m", 4096))), HttpClient.newHttpClient());
            if (adapter.contextLimits().get("beta").get("m") != 4096) throw new AssertionError("profile context limit missing");
            List<ModelStreamChunk> result = adapter.stream(new CompletionRequest(List.of(new Message("user", "hi")), List.of(), true, "m", "beta", 0, 0));
            if (result.isEmpty() || !"beta".equals(result.get(0).delta().get("content"))) throw new AssertionError("selected profile reached wrong endpoint");
            try { adapter.stream(new CompletionRequest(List.of(new Message("user", "hi")), List.of(), true, "m", "missing", 0, 0)); throw new AssertionError("unknown profile silently fell back"); }
            catch (IllegalArgumentException expected) { /* no fallback */ }
            try { adapter.stream(new CompletionRequest(List.of(new Message("user", "hi")), List.of(), true, "", "alpha", 0, 0)); throw new AssertionError("missing request model used fallback"); }
            catch (IllegalArgumentException expected) { /* model is required per request */ }
        } finally { beta.stop(0); }
    }
}
