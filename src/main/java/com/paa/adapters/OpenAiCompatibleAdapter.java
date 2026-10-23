package com.paa.adapters;

import com.paa.gateway.Json;
import com.paa.types.CompletionRequest;
import com.paa.types.Message;
import com.paa.types.ModelAdapter;
import com.paa.types.ModelStreamChunk;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleAdapter implements ModelAdapter {
    public record ProviderProfile(String baseUrl, String apiKeyEnv, Map<String, Integer> modelContextLimits) {}
    private final String apiBaseUrl;
    private final String apiKey;
    private final HttpClient client;
    private final Map<String, ProviderProfile> providerProfiles;

    public OpenAiCompatibleAdapter(String apiBaseUrl, String apiKey) { this(apiBaseUrl, apiKey, HttpClient.newHttpClient()); }
    public OpenAiCompatibleAdapter(String apiBaseUrl, String apiKey, HttpClient client) { this(apiBaseUrl, apiKey, client, Map.of()); }
    public OpenAiCompatibleAdapter(Map<String, ProviderProfile> providerProfiles, HttpClient client) { this("", "", client, providerProfiles); }
    private OpenAiCompatibleAdapter(String apiBaseUrl, String apiKey, HttpClient client, Map<String, ProviderProfile> providerProfiles) { this.apiBaseUrl = apiBaseUrl; this.apiKey = apiKey; this.client = client; this.providerProfiles = Map.copyOf(providerProfiles); }
    @Override public String name() { return "openai-compatible"; }
    @Override public Map<String, Map<String, Integer>> contextLimits() { Map<String, Map<String, Integer>> limits = new LinkedHashMap<>(); providerProfiles.forEach((name, profile) -> limits.put(name, profile.modelContextLimits())); return limits; }
    @Override public List<ModelStreamChunk> stream(CompletionRequest request) {
        if (request.model().isBlank() || request.provider().isBlank()) throw new IllegalArgumentException("Model API request requires model and provider.");
        String selectedBaseUrl = apiBaseUrl, selectedKey = apiKey;
        if (!providerProfiles.isEmpty()) {
            ProviderProfile profile = providerProfiles.get(request.provider());
            if (profile == null) throw new IllegalArgumentException("Unknown selected provider profile.");
            selectedBaseUrl = profile.baseUrl(); selectedKey = "";
            if (profile.apiKeyEnv() != null && !profile.apiKeyEnv().isBlank()) {
                selectedKey = System.getenv(profile.apiKeyEnv());
                if (selectedKey == null || selectedKey.isBlank()) throw new IllegalStateException("Selected provider profile secret is unavailable.");
            }
        } else if (!request.provider().equals("local")) throw new IllegalArgumentException("Unknown selected provider profile.");
        if (selectedBaseUrl.isBlank()) throw new IllegalStateException("Selected provider profile has no base URL.");
        String selectedModel = request.model(); List<Map<String, Object>> messages = request.messages().stream().map(OpenAiCompatibleAdapter::providerMessage).toList(); String payload = Json.stringify(Map.of("model", selectedModel, "messages", messages, "stream", true));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(selectedBaseUrl.replaceAll("/+$", "") + "/chat/completions")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)); if (!selectedKey.isBlank()) builder.header("Authorization", "Bearer " + selectedKey);
        try { HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("provider returned HTTP " + response.statusCode()); return parseStream(response.body()); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("provider request interrupted", error); } catch (java.io.IOException error) { throw new IllegalStateException("provider request failed", error); }
    }
    private static Map<String, Object> providerMessage(Message message) { Map<String, Object> result = new LinkedHashMap<>(); result.put("role", message.role()); result.put("content", message.content()); if (!message.toolCallId().isBlank()) result.put("tool_call_id", message.toolCallId()); return result; }
    private static List<ModelStreamChunk> parseStream(String body) { List<ModelStreamChunk> chunks = new ArrayList<>(); for (String line : body.split("\\R")) { String data = line.trim().startsWith("data:") ? line.trim().substring(5).trim() : ""; if (data.isBlank() || data.equals("[DONE]")) continue; String content = Json.stringField(data, "content"); if (!content.isBlank()) chunks.add(new ModelStreamChunk("text-delta", Map.of("content", content))); String reason = Json.stringField(data, "finish_reason"); if (!reason.isBlank()) chunks.add(new ModelStreamChunk("done", Map.of("finish_reason", reason))); } return chunks; }
}
