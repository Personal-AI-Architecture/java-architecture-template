package com.paa.engine;

import com.paa.types.CompletionRequest;
import com.paa.types.EngineRequest;
import com.paa.types.ModelAdapter;
import com.paa.types.ModelStreamChunk;
import com.paa.types.StreamEvent;
import com.paa.types.ToolDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AgentLoop {
    private final ModelAdapter adapter;
    private final List<ToolDefinition> tools;
    public AgentLoop(ModelAdapter adapter, List<ToolDefinition> tools) { this.adapter = adapter; this.tools = List.copyOf(tools); }
    public List<StreamEvent> run(EngineRequest request, Map<String, String> headers) {
        if (request.metadata().correlationId().isBlank()) return List.of(error("provider_error", "Missing correlation_id."));
        if (header(headers, "X-Actor-ID").isBlank() || header(headers, "X-Actor-Permissions").isBlank()) return List.of(error("provider_error", "Missing required actor headers."));
        List<ModelStreamChunk> chunks;
        try { chunks = adapter.stream(new CompletionRequest(request.messages(), tools, true, "", 0, 0)); }
        catch (RuntimeException error) { return List.of(error("provider_error", error.getMessage())); }
        List<StreamEvent> events = new ArrayList<>();
        for (ModelStreamChunk chunk : chunks) {
            if (chunk.type().equals("text-delta") && chunk.delta().get("content") instanceof String text && !text.isEmpty()) events.add(new StreamEvent("text-delta", Map.of("text", text)));
            else if (List.of("tool-call", "tool-result", "error").contains(chunk.type())) events.add(new StreamEvent(chunk.type(), chunk.delta()));
            else if (chunk.type().equals("done")) { Map<String, Object> data = new HashMap<>(chunk.delta()); data.putIfAbsent("finish_reason", "stop"); events.add(new StreamEvent("done", data)); }
        }
        return events;
    }
    private static StreamEvent error(String code, String message) { return new StreamEvent("error", Map.of("code", code, "message", message == null ? "Provider error." : message)); }
    private static String header(Map<String, String> headers, String name) { return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse("").trim(); }
}
