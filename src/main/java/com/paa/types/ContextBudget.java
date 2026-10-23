package com.paa.types;

import com.paa.gateway.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContextBudget {
    private ContextBudget() {}
    public record Selection(List<Message> messages, int omittedTurns) {}
    public static int limit(Map<String, Map<String, Integer>> limits, String provider, String model) {
        return limits.getOrDefault(provider, Map.of()).getOrDefault(model, 0);
    }
    public static int cost(List<Message> messages, List<ToolDefinition> tools) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (Message message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role()); item.put("content", message.content());
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : message.toolCalls()) calls.add(Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments(), "invalid_arguments", call.invalidArguments()));
            item.put("tool_calls", calls); item.put("tool_call_id", message.toolCallId());
            canonical.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name()); item.put("description", tool.description()); item.put("parameters", tool.parameters());
            item.put("source", tool.source()); item.put("category", tool.category()); item.put("mutates_state", tool.mutatesState()); item.put("required_permissions", tool.requiredPermissions());
            definitions.add(item);
        }
        payload.put("messages", canonical); payload.put("tools", definitions);
        return Json.stringify(payload).getBytes(StandardCharsets.UTF_8).length + 384;
    }
    public static Selection selectCompleteTurns(List<Message> messages, List<ToolDefinition> tools, int limit) {
        List<List<Message>> turns = new ArrayList<>();
        for (Message message : messages) {
            if (message.role().equals("user") || turns.isEmpty()) turns.add(new ArrayList<>());
            turns.get(turns.size() - 1).add(message);
        }
        List<Message> selected = new ArrayList<>(); int kept = 0;
        for (int index = turns.size() - 1; index >= 0; index--) {
            List<Message> candidate = new ArrayList<>(turns.get(index)); candidate.addAll(selected);
            if (cost(candidate, tools) > limit) {
                if (kept == 0) return null;
                break;
            }
            selected = candidate; kept++;
        }
        return new Selection(selected, turns.size() - kept);
    }
}
