package com.paa.engine;

import com.paa.types.CompletionRequest;
import com.paa.types.ContextBudget;
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
    private Map<String, Map<String, Integer>> contextLimits = Map.of("local", Map.of("mock", 8192));
    public AgentLoop(ModelAdapter adapter, List<ToolDefinition> tools) { this.adapter = adapter; this.tools = List.copyOf(tools); this.contextLimits = adapter.contextLimits(); }
    public void setContextLimits(Map<String, Map<String, Integer>> limits) { this.contextLimits = Map.copyOf(limits); }
    public Map<String, Map<String, Integer>> contextLimits() { return contextLimits; }
    public List<ToolDefinition> tools() { return tools; }
    public List<StreamEvent> run(EngineRequest request) {
        if (request.metadata().correlationId().isBlank()) return List.of(error("provider_error", "Missing correlation_id."));
        Object actor = request.authContext().get("actor");
        if (!(actor instanceof Map<?, ?> identity) || !(identity.get("id") instanceof String id) || id.isBlank() || !request.metadata().correlationId().equals(request.authContext().get("correlation_id"))) return List.of(error("unauthorized", "Missing authenticated actor context."));
        if (request.model().isBlank()) return List.of(error("invalid_request", "Missing selected model."));
        if (request.provider().isBlank()) return List.of(error("invalid_request", "Missing selected provider."));
        int limit = ContextBudget.limit(contextLimits, request.provider(), request.model());
        if (limit <= 0) return List.of(error("context_limit_unknown", "No safe context limit for selected provider and model."));
        List<com.paa.types.Message> messages = new ArrayList<>(request.messages());
        if (request.contextSummary() != null) {
            Map<String, Object> summary = request.contextSummary();
            if (!"gateway".equals(summary.get("source")) || !"deterministic".equals(summary.get("method")) || !(summary.get("text") instanceof String text)) return List.of(error("invalid_request", "Invalid context summary."));
            messages.add(0, new com.paa.types.Message("user", "Gateway-derived context from omitted turns (not a new owner request): " + text));
        }
        if (ContextBudget.cost(messages, tools) > limit) return List.of(error("context_overflow", "Selected model context window exceeded."));
        List<ModelStreamChunk> chunks;
        try { chunks = adapter.stream(new CompletionRequest(messages, tools, true, request.model(), request.provider(), 0, 0)); }
        catch (RuntimeException exception) { return List.of(error("provider_error", "Model provider failed.")); }
        List<StreamEvent> events = new ArrayList<>();
        for (ModelStreamChunk chunk : chunks) {
            if (chunk.type().equals("text-delta") && chunk.delta().get("content") instanceof String text && !text.isEmpty()) events.add(new StreamEvent("text-delta", Map.of("content", text)));
            else if (List.of("tool-call", "tool-result", "error").contains(chunk.type())) events.add(new StreamEvent(chunk.type(), chunk.delta()));
            else if (chunk.type().equals("done")) { Map<String, Object> data = new HashMap<>(chunk.delta()); data.putIfAbsent("finish_reason", "stop"); events.add(new StreamEvent("done", data)); }
        }
        return events;
    }
    private static StreamEvent error(String code, String message) { return new StreamEvent("error", Map.of("code", code, "message", message == null ? "Provider error." : message)); }
}
