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
    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient client;

    public OpenAiCompatibleAdapter(String apiBaseUrl, String apiKey, String model) { this(apiBaseUrl, apiKey, model, HttpClient.newHttpClient()); }
    public OpenAiCompatibleAdapter(String apiBaseUrl, String apiKey, String model, HttpClient client) { this.apiBaseUrl = apiBaseUrl; this.apiKey = apiKey; this.model = model; this.client = client; }
    @Override public String name() { return "openai-compatible"; }
    @Override public List<ModelStreamChunk> stream(CompletionRequest request) {
        if (apiBaseUrl.isBlank()) throw new IllegalStateException("OpenAI-compatible adapter requires apiBaseUrl.");
        String selectedModel = request.model().isBlank() ? model : request.model(); List<Map<String, Object>> messages = request.messages().stream().map(OpenAiCompatibleAdapter::providerMessage).toList(); String payload = Json.stringify(Map.of("model", selectedModel, "messages", messages, "stream", true));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBaseUrl.replaceAll("/+$", "") + "/chat/completions")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)); if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try { HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("provider returned HTTP " + response.statusCode()); return parseStream(response.body()); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("provider request interrupted", error); } catch (java.io.IOException error) { throw new IllegalStateException("provider request failed", error); }
    }
    private static Map<String, Object> providerMessage(Message message) { Map<String, Object> result = new LinkedHashMap<>(); result.put("role", message.role()); result.put("content", message.content()); if (!message.toolCallId().isBlank()) result.put("tool_call_id", message.toolCallId()); return result; }
    private static List<ModelStreamChunk> parseStream(String body) { List<ModelStreamChunk> chunks = new ArrayList<>(); for (String line : body.split("\\R")) { String data = line.trim().startsWith("data:") ? line.trim().substring(5).trim() : ""; if (data.isBlank() || data.equals("[DONE]")) continue; String content = Json.stringField(data, "content"); if (!content.isBlank()) chunks.add(new ModelStreamChunk("text-delta", Map.of("content", content))); String reason = Json.stringField(data, "finish_reason"); if (!reason.isBlank()) chunks.add(new ModelStreamChunk("done", Map.of("finish_reason", reason))); } return chunks; }
}
