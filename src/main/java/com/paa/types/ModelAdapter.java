package com.paa.types;

import java.util.List;

public interface ModelAdapter {
    String name();
    List<ModelStreamChunk> stream(CompletionRequest request);
}
