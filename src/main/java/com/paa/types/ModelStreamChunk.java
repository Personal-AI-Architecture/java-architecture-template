package com.paa.types;

import java.util.Map;

public record ModelStreamChunk(String type, Map<String, Object> delta) {}
