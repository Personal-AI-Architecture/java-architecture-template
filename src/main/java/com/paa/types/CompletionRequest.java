package com.paa.types;

import java.util.List;

public record CompletionRequest(List<Message> messages, List<ToolDefinition> tools, boolean stream,
                                String model, String provider, int maxTokens, double temperature) {}
