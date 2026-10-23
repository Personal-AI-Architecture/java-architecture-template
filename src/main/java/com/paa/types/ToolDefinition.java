package com.paa.types;

import java.util.List;
import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parameters,
                             Map<String, String> source, String category, boolean mutatesState,
                             List<String> requiredPermissions) {
    public static ToolDefinition fromCanonical(Map<?, ?> definition) {
        if (!(definition.get("source") instanceof Map<?, ?> rawSource) || !(rawSource.get("id") instanceof String id) || id.isBlank() || !(rawSource.get("kind") instanceof String kind) || kind.isBlank()) throw new IllegalArgumentException("Tool source requires a non-empty id and kind.");
        if (!(definition.get("name") instanceof String name) || name.isBlank()) throw new IllegalArgumentException("Tool name is required.");
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        if (definition.get("parameters") instanceof Map<?, ?> rawParameters) rawParameters.forEach((key, value) -> parameters.put(String.valueOf(key), value));
        String description = definition.get("description") instanceof String value ? value : "";
        String category = definition.get("visibility") instanceof String value ? value : "discoverable";
        return new ToolDefinition(name, description, parameters, Map.of("id", id, "kind", kind), category, Boolean.TRUE.equals(definition.get("mutates_state")), List.of());
    }
}
