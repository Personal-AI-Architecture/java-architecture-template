package com.paa.gateway;

import java.util.Collection;
import java.util.Map;

public final class Json {
    private Json() {}
    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(entry -> quote(String.valueOf(entry.getKey())) + ":" + stringify(entry.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof Collection<?> collection) return collection.stream().map(Json::stringify).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return quote(value.toString());
    }
    public static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    public static String stringField(String json, String field) {
        String needle = "\"" + field + "\""; int start = json.indexOf(needle); if (start < 0) return ""; int colon = json.indexOf(':', start + needle.length()); if (colon < 0) return ""; int quote = json.indexOf('"', colon + 1); if (quote < 0) return ""; StringBuilder result = new StringBuilder(); boolean escaped = false; for (int index = quote + 1; index < json.length(); index++) { char character = json.charAt(index); if (escaped) { result.append(character == 'n' ? '\n' : character == 'r' ? '\r' : character); escaped = false; } else if (character == '\\') escaped = true; else if (character == '"') return result.toString(); else result.append(character); } return "";
    }
}
