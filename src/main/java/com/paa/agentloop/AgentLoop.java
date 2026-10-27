package com.paa.agentloop;

import com.paa.types.CompletionRequest;
import com.paa.types.ContextBudget;
import com.paa.types.AgentLoopRequest;
import com.paa.types.ModelAdapter;
import com.paa.types.ModelStreamChunk;
import com.paa.types.StreamEvent;
import com.paa.types.ToolDefinition;
import com.paa.gateway.Json;
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
    private String sourceIdForTool(String name) {
        for (ToolDefinition tool : tools) if (tool.name().equals(name) && tool.source().get("id") != null && !tool.source().get("id").isBlank()) return tool.source().get("id");
        String trimmed = name.trim(); int separator = -1;
        for (int index = 0; index < trimmed.length(); index++) if (trimmed.charAt(index) == '_' || trimmed.charAt(index) == ':' || trimmed.charAt(index) == '-') { separator = index; break; }
        if (separator > 0) return trimmed.substring(0, separator);
        return "internal";
    }
    private Map<String, Object> toolArguments(Map<String, Object> delta) {
        Object value = delta.get("arguments");
        if (value == null && delta.get("function") instanceof Map<?, ?> function) value = function.get("arguments");
        if (value instanceof Map<?, ?> raw) { Map<String, Object> result = new HashMap<>(); raw.forEach((key, item) -> result.put(String.valueOf(key), item)); return result; }
        if (value instanceof String encoded) {
            try { Object parsed = Json.parse(encoded); if (parsed instanceof Map<?, ?> raw) { Map<String, Object> result = new HashMap<>(); raw.forEach((key, item) -> result.put(String.valueOf(key), item)); return result; } }
            catch (IllegalArgumentException ignored) { }
        }
        return new HashMap<>();
    }
    private String toolOutput(Object value) { return value instanceof String text ? text : Json.stringify(value); }
    public List<StreamEvent> run(AgentLoopRequest request) {
        if (request.authContext().keySet().stream().anyMatch(key -> !List.of("actor", "authentication_id", "authenticated_at", "expires_at", "correlation_id").contains(key))) return List.of(error("auth_error", "Authentication context was rejected."));
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
            else if (chunk.type().equals("tool-call")) {
                Map<String, Object> data = new HashMap<>(chunk.delta());
                String name = data.get("name") instanceof String value ? value : data.get("function") instanceof Map<?, ?> function && function.get("name") instanceof String value ? value : "";
                data.put("name", name);
                data.putIfAbsent("source_id", sourceIdForTool(name));
                data.put("arguments", toolArguments(data));
                events.add(new StreamEvent("tool-call", data));
            }
            else if (chunk.type().equals("tool-result")) {
                Map<String, Object> data = new HashMap<>(chunk.delta());
                if (data.containsKey("output")) data.put("output", toolOutput(data.get("output")));
                events.add(new StreamEvent("tool-result", data));
            }
            else if (chunk.type().equals("error")) { events.add(new StreamEvent("error", safeModelError(chunk.delta()))); break; }
            else if (chunk.type().equals("done")) { Map<String, Object> data = new HashMap<>(chunk.delta()); data.putIfAbsent("finish_reason", "stop"); events.add(new StreamEvent("done", data)); }
        }
        return events;
    }
    private static StreamEvent error(String code, String message) { return new StreamEvent("error", Map.of("code", code, "message", message == null ? "Provider error." : message)); }
    private static Map<String, Object> safeModelError(Map<String, Object> delta) {
        Object code = delta.get("code");
        if ("model_unavailable".equals(code)) return Map.of("code", "model_unavailable", "message", "The selected model is unavailable through the selected provider.");
        if ("capability_unsupported".equals(code) && delta.get("capability") instanceof String capability && capability.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) return Map.of("code", "capability_unsupported", "capability", capability, "message", "The selected model does not support the requested capability.");
        return Map.of("code", "provider_error", "message", "Model provider failed.");
    }
}
