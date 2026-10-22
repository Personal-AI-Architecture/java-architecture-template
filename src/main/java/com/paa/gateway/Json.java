package com.paa.gateway;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    public static Object parse(String source) {
        Parser parser = new Parser(source);
        Object value = parser.value(0);
        parser.space();
        if (parser.index != source.length()) throw new IllegalArgumentException("Unexpected JSON suffix");
        return value;
    }

    private static final class Parser {
        private final String source;
        private int index;
        private Parser(String source) { this.source = source; }
        private void space() { while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++; }
        private char peek() { space(); if (index >= source.length()) throw new IllegalArgumentException("Incomplete JSON"); return source.charAt(index); }
        private void expect(char wanted) { if (peek() != wanted) throw new IllegalArgumentException("Expected " + wanted); index++; }
        private Object value(int depth) {
            if (depth > 64) throw new IllegalArgumentException("JSON nesting limit exceeded");
            char next = peek();
            if (next == '"') return string();
            if (next == '{') {
                index++; Map<String, Object> result = new LinkedHashMap<>();
                if (peek() == '}') { index++; return result; }
                while (true) {
                    if (peek() != '"') throw new IllegalArgumentException("Object key must be a string");
                    String key = string(); expect(':'); result.put(key, value(depth + 1));
                    char delimiter = peek(); index++;
                    if (delimiter == '}') return result;
                    if (delimiter != ',') throw new IllegalArgumentException("Invalid object delimiter");
                }
            }
            if (next == '[') {
                index++; List<Object> result = new ArrayList<>();
                if (peek() == ']') { index++; return result; }
                while (true) {
                    result.add(value(depth + 1)); char delimiter = peek(); index++;
                    if (delimiter == ']') return result;
                    if (delimiter != ',') throw new IllegalArgumentException("Invalid array delimiter");
                }
            }
            for (String literal : List.of("true", "false", "null")) {
                if (source.startsWith(literal, index)) { index += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
            }
            int start = index;
            if (next == '-' || Character.isDigit(next)) {
                index++; while (index < source.length() && "0123456789.eE+-".indexOf(source.charAt(index)) >= 0) index++;
                try { return new java.math.BigDecimal(source.substring(start, index)); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid JSON number", error); }
            }
            throw new IllegalArgumentException("Invalid JSON value");
        }
        private String string() {
            expect('"'); StringBuilder result = new StringBuilder();
            while (index < source.length()) {
                char next = source.charAt(index++);
                if (next == '"') return result.toString();
                if (next != '\\') { if (next < 0x20) throw new IllegalArgumentException("Invalid JSON control character"); result.append(next); continue; }
                if (index >= source.length()) throw new IllegalArgumentException("Incomplete JSON escape");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"' -> result.append('"'); case '\\' -> result.append('\\'); case '/' -> result.append('/');
                    case 'b' -> result.append('\b'); case 'f' -> result.append('\f'); case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                    case 'u' -> { if (index + 4 > source.length()) throw new IllegalArgumentException("Incomplete Unicode escape");
                        try { result.append((char) Integer.parseInt(source.substring(index, index + 4), 16)); }
                        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid Unicode escape", error); }
                        index += 4; }
                    default -> throw new IllegalArgumentException("Invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }
    }
}
