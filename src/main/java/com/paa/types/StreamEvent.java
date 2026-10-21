package com.paa.types;

import java.util.Map;

public record StreamEvent(String event, Map<String, Object> data) {}
