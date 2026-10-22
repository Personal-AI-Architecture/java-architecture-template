package com.paa.types;

import java.util.List;
import java.util.Map;

public record Message(String role, List<Map<String, Object>> content, List<ToolCall> toolCalls, String toolCallId) {
    public Message(String role, List<Map<String, Object>> content) { this(role, List.copyOf(content), List.of(), ""); }
    public Message(String role, String text) { this(role, List.of(Map.of("type", "text", "text", text))); }
}
