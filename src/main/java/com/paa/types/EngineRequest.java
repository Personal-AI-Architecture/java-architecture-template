package com.paa.types;

import java.util.List;
import java.util.Map;

public record EngineRequest(List<Message> messages, EngineRequestMetadata metadata, String model, Map<String, Object> authContext) {}
