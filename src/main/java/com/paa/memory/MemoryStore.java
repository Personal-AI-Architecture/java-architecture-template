package com.paa.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryStore {
    public record Entry(String kind, String id, String conversationId, String role, String content) {}
    private final String memoryRoot;
    private final List<Entry> entries = new ArrayList<>();

    public MemoryStore(String memoryRoot) { this.memoryRoot = memoryRoot; }
    public synchronized void record(Entry entry) { entries.add(entry); }
    public synchronized List<Entry> history() { return entries.stream().filter(entry -> entry.kind().equals("message")).toList(); }
    public synchronized Map<String, Object> export() {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("memory_root", memoryRoot); result.put("entries", List.copyOf(entries)); result.put("history", history()); return result;
    }
}
