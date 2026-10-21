package com.paa.types;

import java.util.List;
import java.util.Map;

public record EngineRequestMetadata(String correlationId, String conversationId, String trigger,
                                    Map<String, Object> clientContext, String actorId,
                                    List<String> actorPermissions, List<String> allowedToolSources) {}
