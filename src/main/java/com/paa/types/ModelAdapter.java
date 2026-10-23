package com.paa.types;

import java.util.List;
import java.util.Map;

public interface ModelAdapter {
    String name();
    List<ModelStreamChunk> stream(CompletionRequest request);
    default Map<String, Map<String, Integer>> contextLimits() { return Map.of("local", Map.of("mock", 8192)); }
}
