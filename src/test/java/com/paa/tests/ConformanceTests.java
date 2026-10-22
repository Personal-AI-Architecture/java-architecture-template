package com.paa.tests;

import com.paa.adapters.MockAdapter;
import com.paa.engine.AgentLoop;
import com.paa.gateway.Gateway;
import java.util.Map;

public final class ConformanceTests {
    private ConformanceTests() {}
    public static void main(String[] args) { Gateway gateway = new Gateway(new AgentLoop(new MockAdapter(), java.util.List.of()), null); Map<String, String> headers = Map.of("X-Actor-ID", "actor", "X-Actor-Permissions", "memory:read"); require(gateway.handle("POST", "/conversations/messages", "{\"messages\":[],\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}", headers).status() == 400, "internal engine shape was accepted"); require(gateway.handle("POST", "/conversations/messages", "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}", Map.of()).status() == 401, "actor headers were not enforced"); require(gateway.handle("POST", "/conversations/messages", "{\"content\":\"hello\"}", headers).status() == 400, "old string content was accepted"); require(gateway.handle("POST", "/conversations/messages", "{\"content\":[}", headers).status() == 400, "malformed JSON was accepted"); System.out.println("Conformance tests passed."); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
