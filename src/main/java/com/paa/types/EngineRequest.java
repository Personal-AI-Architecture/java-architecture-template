package com.paa.types;

import java.util.List;

public record EngineRequest(List<Message> messages, EngineRequestMetadata metadata) {}
