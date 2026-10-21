package com.paa.config;

import java.util.List;
import java.util.Map;

public record RuntimeConfiguration(String memoryRoot, String providerAdapter,
                                   String authMode, List<String> toolSources) {
    public static RuntimeConfiguration load(Map<String, Object> values) {
        Map<String, Object> source = values;
        Object nested = values.get("default");
        if (nested instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
            source = typed;
        }
        return new RuntimeConfiguration(
                text(source.get("memory_root"), ".paa-memory"),
                text(source.get("provider_adapter"), "mock"),
                text(source.get("auth_mode"), "enforced"),
                strings(source.getOrDefault("tool_sources", List.of("memory"))));
    }

    private static String text(Object value, String fallback) { return value instanceof String text && !text.isBlank() ? text : fallback; }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("tool_sources must be a list of strings");
        return list.stream().map(item -> item instanceof String text ? text : throwType()).toList();
    }
    private static String throwType() { throw new IllegalArgumentException("tool_sources must be a list of strings"); }
}
