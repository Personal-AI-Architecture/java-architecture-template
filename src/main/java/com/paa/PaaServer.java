package com.paa;

import com.paa.adapters.AdapterLoader;
import com.paa.adapters.OpenAiCompatibleAdapter;
import com.paa.engine.AgentLoop;
import com.paa.gateway.Gateway;
import com.paa.gateway.Json;
import com.paa.memory.MemoryStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public final class PaaServer {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private PaaServer() {}
    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("PAA_DEMO_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Set PAA_DEMO_API_KEY to a local test token before starting.");
        String host = args.length > 0 ? args[0] : "127.0.0.1"; int port = args.length > 1 ? Integer.parseInt(args[1]) : 8000;
        if (!System.getenv().getOrDefault("RUNTIME_AUTH_MODE", "enforced").equals("enforced")) throw new IllegalArgumentException("HTTP starter requires enforced Auth.");
        String providerAdapter = System.getenv().getOrDefault("RUNTIME_PROVIDER_ADAPTER", "mock");
        String selectedModel = "mock", selectedProvider = "local";
        OpenAiCompatibleAdapter openAi = new OpenAiCompatibleAdapter("", "");
        if (providerAdapter.equals("openai-compatible")) {
            Map<String, OpenAiCompatibleAdapter.ProviderProfile> profiles;
            String file = System.getenv("PAA_ADAPTER_CONFIG_FILE"), raw = System.getenv("PAA_MODEL_PROFILES_JSON");
            if (file != null && !file.isBlank()) {
                Object document = Json.parse(Files.readString(Path.of(file)));
                if (!(document instanceof Map<?, ?> values)) throw new IllegalArgumentException("Invalid adapter configuration.");
                profiles = parseProfiles(values.get("provider_profiles"));
            } else if (raw != null && !raw.isBlank()) profiles = parseProfiles(Json.parse(raw));
            else {
                selectedModel = System.getenv("PAA_MODEL_ID");
                String baseUrl = System.getenv("PAA_MODEL_API_BASE_URL"), limitText = System.getenv("PAA_MODEL_CONTEXT_LIMIT");
                if (selectedModel == null || selectedModel.isBlank() || baseUrl == null || baseUrl.isBlank() || limitText == null) throw new IllegalArgumentException("OpenAI-compatible mode requires provider profiles or PAA_MODEL_ID, PAA_MODEL_API_BASE_URL, and PAA_MODEL_CONTEXT_LIMIT.");
                int limit = Integer.parseInt(limitText);
                if (limit <= 0) throw new IllegalArgumentException("PAA_MODEL_CONTEXT_LIMIT must be positive.");
                profiles = Map.of("local", new OpenAiCompatibleAdapter.ProviderProfile(baseUrl, "PAA_MODEL_API_KEY", Map.of(selectedModel, limit)));
            }
            if (profiles.isEmpty()) throw new IllegalArgumentException("Provider profiles are required.");
            selectedProvider = System.getenv("PAA_DEFAULT_PROVIDER");
            if (selectedProvider == null || selectedProvider.isBlank()) selectedProvider = profiles.keySet().stream().sorted().findFirst().orElseThrow();
            OpenAiCompatibleAdapter.ProviderProfile defaultProfile = profiles.get(selectedProvider);
            if (defaultProfile == null) throw new IllegalArgumentException("Default provider profile is not configured.");
            if (System.getenv("PAA_MODEL_ID") != null && !System.getenv("PAA_MODEL_ID").isBlank()) selectedModel = System.getenv("PAA_MODEL_ID");
            if (selectedModel == null || selectedModel.equals("mock")) selectedModel = defaultProfile.modelContextLimits().keySet().stream().sorted().findFirst().orElseThrow();
            openAi = new OpenAiCompatibleAdapter(profiles, HttpClient.newHttpClient());
        }
        String memoryRoot = System.getenv().getOrDefault("RUNTIME_MEMORY_ROOT", ".paa-memory");
        MemoryStore memory = new MemoryStore(memoryRoot);
        if (memory.readPreferences() == null) memory.writePreferences(new MemoryStore.Preferences(selectedModel, selectedProvider));
        String defaultModel = selectedModel, defaultProvider = selectedProvider;
        Gateway gateway = new Gateway(new AgentLoop(AdapterLoader.load(providerAdapter, openAi), List.of()), memory, () -> defaultModel, () -> defaultProvider);
        gateway.setCurrentSelection(() -> { try { return memory.readPreferences(); } catch (IOException error) { throw new IllegalStateException(error); } });
        gateway.enablePersistence(memoryRoot);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0); server.createContext("/", exchange -> handle(exchange, gateway, memory, apiKey)); server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); server.start();
        System.out.println("PAA Java template listening at http://" + host + ":" + port); System.out.println("Try: curl http://" + host + ":" + port + "/health");
    }
    private static Map<String, OpenAiCompatibleAdapter.ProviderProfile> parseProfiles(Object source) {
        if (!(source instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Provider profiles must be an object.");
        Map<String, OpenAiCompatibleAdapter.ProviderProfile> profiles = new LinkedHashMap<>();
        for (var item : raw.entrySet()) {
            if (!(item.getKey() instanceof String name) || !(item.getValue() instanceof Map<?, ?> value)
                || !(value.get("base_url") instanceof String baseUrl) || baseUrl.isBlank()
                || !(value.get("model_context_limits") instanceof Map<?, ?> limitsRaw)) throw new IllegalArgumentException("Invalid provider profile.");
            Map<String, Integer> limits = new LinkedHashMap<>();
            for (var limit : limitsRaw.entrySet()) {
                if (!(limit.getKey() instanceof String model) || !(limit.getValue() instanceof Number number) || number.intValue() <= 0) throw new IllegalArgumentException("Invalid model context limit.");
                limits.put(model, number.intValue());
            }
            String secret = value.get("api_key_env") instanceof String key ? key : null;
            profiles.put(name, new OpenAiCompatibleAdapter.ProviderProfile(baseUrl, secret, limits));
        }
        return profiles;
    }
    private static void handle(HttpExchange exchange, Gateway gateway, MemoryStore memory, String apiKey) throws IOException {
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
        if (exchange.getRequestMethod().equals("POST") || exchange.getRequestMethod().equals("PUT")) {
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
        if (exchange.getRequestURI().getPath().equals("/owner/preferences")) {
            if (exchange.getRequestMethod().equals("GET")) {
                MemoryStore.Preferences preference;
                try { preference = memory.readPreferences(); }
                catch (IOException error) { preference = null; }
                if (preference == null) send(exchange, 503, "application/json", "{\"code\":\"selection_unavailable\",\"message\":\"Preferences unavailable.\"}");
                else send(exchange, 200, "application/json", Json.stringify(Map.of("model", preference.model(), "active_provider_profile", preference.activeProviderProfile())));
                return;
            }
            if (exchange.getRequestMethod().equals("PUT")) {
                Object parsed;
                try { parsed = Json.parse(body); }
                catch (IllegalArgumentException error) { parsed = null; }
                if (!(parsed instanceof Map<?, ?> value) || !(value.get("model") instanceof String model) || model.isBlank()
                    || !(value.get("active_provider_profile") instanceof String provider) || provider.isBlank()) {
                    send(exchange, 400, "application/json", "{\"code\":\"invalid_request\",\"message\":\"Model and active_provider_profile are required.\"}");
                    return;
                }
                memory.writePreferences(new MemoryStore.Preferences(model, provider));
                send(exchange, 200, "application/json", Json.stringify(Map.of("model", model, "active_provider_profile", provider)));
                return;
            }
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
