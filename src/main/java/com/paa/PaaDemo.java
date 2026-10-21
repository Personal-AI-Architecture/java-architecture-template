package com.paa;

import com.paa.adapters.AdapterLoader;
import com.paa.adapters.OpenAiCompatibleAdapter;
import com.paa.engine.AgentLoop;
import com.paa.gateway.Gateway;
import com.paa.types.StreamEvent;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PaaDemo {
    private PaaDemo() {}
    public static void main(String[] args) {
        Gateway gateway = new Gateway(new AgentLoop(AdapterLoader.load("mock", new OpenAiCompatibleAdapter("", "", "")), java.util.List.of()), null);
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("X-Actor-ID", "demo-user"); headers.put("X-Actor-Permissions", "memory:read,memory:write");
        Gateway.Response response = gateway.handle("POST", "/conversations/messages", "{\"content\":\"Explain what PAA is.\"}", headers);
        System.out.println("PAA Java template demo\n======================"); System.out.println("Request: POST /conversations/messages"); System.out.println("Conversation: " + response.headers().get("X-Conversation-ID")); System.out.println("Stream:");
        for (StreamEvent event : response.stream()) System.out.println("  " + event.event() + ": " + com.paa.gateway.Json.stringify(event.data()));
        System.out.println("\nPAA boundary demonstrated: Gateway -> Agent Loop -> Model Adapter -> stream -> memory.");
    }
}
