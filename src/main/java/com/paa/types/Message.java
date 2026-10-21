package com.paa.types;

import java.util.List;

public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public Message(String role, String content) { this(role, content, List.of(), ""); }
}
