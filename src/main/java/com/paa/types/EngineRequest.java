package com.paa.types;

import java.util.List;
import java.util.Map;

public record EngineRequest(List<Message> messages, EngineRequestMetadata metadata, String model, String provider, Map<String, Object> authContext, Map<String, Object> contextSummary) {}
