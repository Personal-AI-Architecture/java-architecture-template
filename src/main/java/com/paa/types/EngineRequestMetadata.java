package com.paa.types;

import java.util.Map;

public record EngineRequestMetadata(String correlationId, String conversationId, String trigger,
                                    Map<String, Object> clientContext) {}
