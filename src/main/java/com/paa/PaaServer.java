package com.paa;

import com.paa.adapters.AdapterLoader;
import com.paa.adapters.OpenAiCompatibleAdapter;
import com.paa.engine.AgentLoop;
import com.paa.gateway.Gateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PaaServer {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private PaaServer() {}
    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("PAA_DEMO_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Set PAA_DEMO_API_KEY to a local test token before starting.");
        String host = args.length > 0 ? args[0] : "127.0.0.1"; int port = args.length > 1 ? Integer.parseInt(args[1]) : 8000;
        if (!System.getenv().getOrDefault("RUNTIME_AUTH_MODE", "enforced").equals("enforced")) throw new IllegalArgumentException("HTTP starter requires enforced Auth.");
        String providerAdapter = System.getenv().getOrDefault("RUNTIME_PROVIDER_ADAPTER", "mock");
        String selectedModel = providerAdapter.equals("openai-compatible") ? System.getenv("PAA_MODEL_ID") : "mock";
        OpenAiCompatibleAdapter openAi = new OpenAiCompatibleAdapter("", "");
        if (providerAdapter.equals("openai-compatible")) {
            String baseUrl = System.getenv("PAA_MODEL_API_BASE_URL");
            String limitText = System.getenv("PAA_MODEL_CONTEXT_LIMIT");
            if (selectedModel == null || selectedModel.isBlank() || baseUrl == null || baseUrl.isBlank() || limitText == null) throw new IllegalArgumentException("OpenAI-compatible mode requires PAA_MODEL_ID, PAA_MODEL_API_BASE_URL, and PAA_MODEL_CONTEXT_LIMIT.");
            int limit = Integer.parseInt(limitText);
            if (limit <= 0) throw new IllegalArgumentException("PAA_MODEL_CONTEXT_LIMIT must be positive.");
            openAi = new OpenAiCompatibleAdapter(Map.of("local", new OpenAiCompatibleAdapter.ProviderProfile(baseUrl, "PAA_MODEL_API_KEY", Map.of(selectedModel, limit))), HttpClient.newHttpClient());
        }
        Gateway gateway = new Gateway(new AgentLoop(AdapterLoader.load(providerAdapter, openAi), java.util.List.of()), null, () -> selectedModel, () -> "local");
        gateway.enablePersistence(System.getenv().getOrDefault("RUNTIME_MEMORY_ROOT", ".paa-memory"));
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0); server.createContext("/", exchange -> handle(exchange, gateway, apiKey)); server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); server.start();
        System.out.println("PAA Java template listening at http://" + host + ":" + port); System.out.println("Try: curl http://" + host + ":" + port + "/health");
    }
    private static void handle(HttpExchange exchange, Gateway gateway, String apiKey) throws IOException {
        if (!(exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/health"))) {
            String supplied = exchange.getRequestHeaders().getFirst("X-API-Key");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) supplied = authorization.substring(7).trim();
            if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, "application/json", "{\"code\":\"unauthenticated\",\"message\":\"Authentication required.\"}");
                return;
            }
        }
        String body = "";
        if (exchange.getRequestMethod().equals("POST")) {
            String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
            long length;
            try { length = Long.parseLong(lengthHeader); } catch (Exception error) { length = -1; }
            if (length < 0 || length > MAX_REQUEST_BYTES) {
                send(exchange, length < 0 ? 411 : 413, "application/json", "{\"code\":\"request_too_large\",\"message\":\"Request body too large.\"}");
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                send(exchange, 413, "application/json", "{\"code\":\"request_too_large\",\"message\":\"Request body too large.\"}");
                return;
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Actor-ID", "local-owner");
        headers.put("X-Actor-Permissions", "memory:read,memory:write");
        Gateway.Response response = gateway.handle(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body, headers);
        boolean stream = response.headers().getOrDefault("Content-Type", "").equals("text/event-stream");
        String output = stream ? response.stream().stream().map(event -> "event: " + event.event() + "\ndata: " + com.paa.gateway.Json.stringify(event.data()) + "\n\n").reduce("", String::concat) : response.body();
        response.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
        send(exchange, response.status(), stream ? "text/event-stream" : "application/json", output);
    }
    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream target = exchange.getResponseBody()) { target.write(bytes); }
    }
}
