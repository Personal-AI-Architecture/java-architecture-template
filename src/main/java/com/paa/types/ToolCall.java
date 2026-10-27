package com.paa.types;

import java.util.Map;

public record ToolCall(String id, String sourceId, String name, Map<String, Object> arguments, boolean invalidArguments) {}
