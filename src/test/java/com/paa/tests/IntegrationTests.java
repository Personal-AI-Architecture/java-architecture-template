package com.paa.tests;

import com.paa.adapters.MockAdapter;
import com.paa.engine.AgentLoop;
import com.paa.gateway.Gateway;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IntegrationTests {
    private IntegrationTests() {}
    public static void main(String[] args) { Gateway gateway = new Gateway(new AgentLoop(new MockAdapter("integration-ok"), java.util.List.of()), null); Map<String, String> headers = new LinkedHashMap<>(); headers.put("X-Actor-ID", "integration"); headers.put("X-Actor-Permissions", "memory:read,memory:write"); Gateway.Response response = gateway.handle("POST", "/conversations/messages", "{\"content\":\"hello\"}", headers); require(response.status() == 200, "canonical route did not return 200"); require(response.stream().stream().anyMatch(event -> event.event().equals("done")), "canonical route did not finish"); String id = response.headers().get("X-Conversation-ID"); require(gateway.handle("GET", "/conversations/" + id, "", Map.of()).body().contains("integration-ok"), "assistant message was not persisted"); System.out.println("Integration tests passed."); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
