package com.paa;

import com.paa.adapters.AdapterLoader;
import com.paa.adapters.MockAdapter;
import com.paa.adapters.OpenAiCompatibleAdapter;
import com.paa.config.RuntimeConfiguration;
import com.paa.agentloop.AgentLoop;
import com.paa.gateway.Gateway;
import com.paa.memory.MemoryStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PaaAcceptance {
    private PaaAcceptance() {}
    public static void main(String[] args) {
        try { run(); System.out.println("Acceptance check passed."); } catch (RuntimeException error) { System.err.println("Acceptance check failed: " + error.getMessage()); System.exit(1); }
    }
    private static void run() {
        RuntimeConfiguration configuration = RuntimeConfiguration.load(Map.of("default", Map.of("memory_root", ".paa-memory", "provider_adapter", "mock", "auth_mode", "enforced", "tool_sources", List.of("memory")))); if (!AdapterLoader.load(configuration.providerAdapter(), new OpenAiCompatibleAdapter("", "")).name().equals("mock")) throw new IllegalStateException("mock adapter selection failed"); if (!AdapterLoader.load("openai-compatible", new OpenAiCompatibleAdapter("", "")).name().equals("openai-compatible")) throw new IllegalStateException("provider adapter selection failed"); try { AdapterLoader.load("unknown", new OpenAiCompatibleAdapter("", "")); throw new IllegalStateException("unknown adapter accepted"); } catch (IllegalArgumentException expected) { }
        MemoryStore memory = new MemoryStore(configuration.memoryRoot()); Gateway gateway = new Gateway(new AgentLoop(new MockAdapter("acceptance-ok"), List.of()), memory); Map<String, String> headers = new LinkedHashMap<>(); headers.put("x-actor-id", "actor-acceptance"); headers.put("x-actor-permissions", "memory:read,memory:write"); Gateway.Response response = gateway.handle("POST", "/conversations/messages", "{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}", headers); if (response.status() != 200 || response.stream().size() < 2) throw new IllegalStateException("canonical route did not stream"); String id = response.headers().get("X-Conversation-ID"); if (gateway.handle("GET", "/conversations", "", Map.of()).body().contains("\"total\":0")) throw new IllegalStateException("conversation list was empty"); if (!gateway.handle("GET", "/conversations/" + id, "", Map.of()).body().contains("\"messages\"")) throw new IllegalStateException("conversation detail missing messages"); if (memory.history().size() < 2) throw new IllegalStateException("memory history was not exported"); if (!com.paa.checks.PaaChecks.run(Path.of("."), null).isEmpty()) throw new IllegalStateException("native PAA checks failed");
    }
}
