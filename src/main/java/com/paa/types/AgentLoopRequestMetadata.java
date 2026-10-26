package com.paa.types;

import java.util.Map;

public record AgentLoopRequestMetadata(String correlationId, String conversationId, String trigger,
                                    Map<String, Object> clientContext) {}
