package com.paa.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class MemoryStore {
    public record Preferences(String model, String activeProviderProfile) {}
    public record Entry(String kind, String id, String conversationId, String role, Object content, String model, String provider) {
        public Entry(String kind, String id, String conversationId, String role, Object content) { this(kind, id, conversationId, role, content, "", ""); }
    }
    private final String memoryRoot;
    private final List<Entry> entries = new ArrayList<>();

    public MemoryStore(String memoryRoot) { this.memoryRoot = memoryRoot; }
    public synchronized Preferences readPreferences() throws IOException {
        Path path = Path.of(memoryRoot, "preferences.properties");
        if (!Files.exists(path)) return null;
        Properties value = new Properties();
        try (var input = Files.newInputStream(path)) { value.load(input); }
        String model = value.getProperty("model"), provider = value.getProperty("active_provider_profile");
        if (model == null || model.isBlank() || provider == null || provider.isBlank()) {
            throw new IOException("Invalid owner preferences");
        }
        return new Preferences(model, provider);
    }
    public synchronized void writePreferences(Preferences preference) throws IOException {
        if (preference == null || preference.model() == null || preference.model().isBlank()
            || preference.activeProviderProfile() == null || preference.activeProviderProfile().isBlank()) {
            throw new IllegalArgumentException("Model and provider are required");
        }
        Files.createDirectories(Path.of(memoryRoot));
        Path temporary = Files.createTempFile(Path.of(memoryRoot), "preferences-", ".tmp");
        try {
            Properties value = new Properties();
            value.setProperty("model", preference.model());
            value.setProperty("active_provider_profile", preference.activeProviderProfile());
            try (var output = Files.newOutputStream(temporary)) { value.store(output, "Owner preferences"); }
            Files.move(temporary, Path.of(memoryRoot, "preferences.properties"), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized void record(Entry entry) { entries.add(entry); }
    public synchronized List<Entry> history() { return entries.stream().filter(entry -> entry.kind().equals("message")).toList(); }
    public synchronized Map<String, Object> export() {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("memory_root", memoryRoot); result.put("entries", List.copyOf(entries)); result.put("history", history());
        try { Preferences preference = readPreferences(); if (preference != null) result.put("preferences", Map.of("model", preference.model(), "active_provider_profile", preference.activeProviderProfile())); }
        catch (IOException error) { throw new IllegalStateException("Owner preferences could not be exported", error); }
        return result;
    }
}
