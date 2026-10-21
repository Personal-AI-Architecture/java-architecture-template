package com.paa.adapters;

import com.paa.types.ModelAdapter;

public final class AdapterLoader {
    private AdapterLoader() {}
    public static ModelAdapter load(String provider, OpenAiCompatibleAdapter openAi) {
        return switch (provider) {
            case "mock", "local" -> new MockAdapter();
            case "openai-compatible" -> openAi;
            default -> throw new IllegalArgumentException("unsupported provider adapter: " + provider);
        };
    }
}
