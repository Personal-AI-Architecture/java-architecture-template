package com.paa.adapters;

import com.paa.types.CompletionRequest;
import com.paa.types.ModelAdapter;
import com.paa.types.ModelStreamChunk;
import java.util.List;
import java.util.Map;

public final class MockAdapter implements ModelAdapter {
    private final String response;
    public MockAdapter() { this("mock response"); }
    public MockAdapter(String response) { this.response = response; }
    @Override public String name() { return "mock"; }
    @Override public List<ModelStreamChunk> stream(CompletionRequest request) {
        return List.of(new ModelStreamChunk("text-delta", Map.of("content", response)),
                new ModelStreamChunk("done", Map.of("finish_reason", "stop")));
    }
}
