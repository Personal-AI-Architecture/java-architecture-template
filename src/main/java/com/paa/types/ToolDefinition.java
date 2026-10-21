package com.paa.types;

import java.util.List;
import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parameters,
                             String source, String category, boolean mutatesState,
                             List<String> requiredPermissions) {}
