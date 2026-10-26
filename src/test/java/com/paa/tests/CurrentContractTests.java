package com.paa.tests;

import com.paa.agentloop.AgentLoop;
import com.paa.gateway.Gateway;
import com.paa.gateway.Json;
import com.paa.types.CompletionRequest;
import com.paa.types.ContextBudget;
import com.paa.types.AgentLoopRequest;
import com.paa.types.AgentLoopRequestMetadata;
import com.paa.types.Message;
import com.paa.types.ModelAdapter;
import com.paa.types.ModelStreamChunk;
import com.paa.types.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CurrentContractTests {
    private CurrentContractTests() {}
    private static final class RecordingAdapter implements ModelAdapter {
        private final List<CompletionRequest> requests = new ArrayList<>();
        @Override public String name() { return "recording"; }
        @Override public List<ModelStreamChunk> stream(CompletionRequest request) {
            requests.add(request);
            return List.of(new ModelStreamChunk("text-delta", Map.of("content", "answer")), new ModelStreamChunk("done", Map.of("finish_reason", "stop")));
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        RecordingAdapter adapter = new RecordingAdapter();
        AgentLoop loop = new AgentLoop(adapter, List.of());
        loop.setContextLimits(Map.of("alpha", Map.of("m1", 8192), "beta", Map.of("m2", 8192)));
        String[] selected = {"alpha", "m1"};
        Gateway gateway = new Gateway(loop, null, () -> selected[1], () -> selected[0]);
        Map<String, String> headers = Map.of("X-Actor-ID", "owner", "X-Actor-Permissions", "read");
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}";
        Gateway.Response first = gateway.handle("POST", "/conversations/messages", body, headers);
        String id = first.headers().get("X-Conversation-ID");
        selected[0] = "beta"; selected[1] = "m2";
        Gateway.Response second = gateway.handle("POST", "/conversations/" + id + "/messages", body, headers);
        require(first.status() == 200 && second.status() == 200, "provider switch request failed");
        require(adapter.requests.size() == 2 && adapter.requests.get(0).provider().equals("alpha") && adapter.requests.get(1).provider().equals("beta") && adapter.requests.get(1).model().equals("m2"), "per-request provider/model did not reach adapter");
        Map<?, ?> detail = (Map<?, ?>) Json.parse(gateway.handle("GET", "/conversations/" + id, "", Map.of()).body());
        List<?> messages = (List<?>) detail.get("messages");
        require(((Map<?, ?>) messages.get(1)).get("provider").equals("alpha") && ((Map<?, ?>) messages.get(3)).get("model").equals("m2"), "saved reply provenance missing");

        RecordingAdapter boundedAdapter = new RecordingAdapter();
        AgentLoop bounded = new AgentLoop(boundedAdapter, List.of());
        bounded.setContextLimits(Map.of());
        Gateway boundedGateway = new Gateway(bounded, null);
        Gateway.Response unknown = boundedGateway.handle("POST", "/conversations/messages", body, headers);
        require(unknown.status() == 503 && unknown.body().contains("context_limit_unknown"), "unknown limit did not block");
        bounded.setContextLimits(Map.of("local", Map.of("mock", 500)));
        Gateway.Response overflow = boundedGateway.handle("POST", "/conversations/messages", "{\"content\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(1000) + "\"}]}", headers);
        require(overflow.status() == 422 && overflow.body().contains("context_overflow") && boundedAdapter.requests.isEmpty(), "oversized current message reached adapter");
        int oneCost = ContextBudget.cost(List.of(new Message("user", "hello")), List.of());
        bounded.setContextLimits(Map.of("local", Map.of("mock", oneCost + 8)));
        Gateway.Response start = boundedGateway.handle("POST", "/conversations/messages", body, headers);
        Gateway.Response continued = boundedGateway.handle("POST", "/conversations/" + start.headers().get("X-Conversation-ID") + "/messages", body, headers);
        require(start.status() == 200 && continued.status() == 200 && continued.stream().get(0).event().equals("context_notice"), "omitted turn notice missing");
        Map<?, ?> saved = (Map<?, ?>) Json.parse(boundedGateway.handle("GET", "/conversations/" + start.headers().get("X-Conversation-ID"), "", Map.of()).body());
        require(((List<?>) saved.get("messages")).size() == 4, "full history was lost");
        RecordingAdapter summaryAdapter = new RecordingAdapter();
        AgentLoop summaryLoop = new AgentLoop(summaryAdapter, List.of());
        List<Message> original = List.of(new Message("user", "current"));
        summaryLoop.run(new AgentLoopRequest(original, new AgentLoopRequestMetadata("corr", "", "message", Map.of()), "mock", "local", Map.of("actor", Map.of("id", "owner"), "correlation_id", "corr"), Map.of("source", "gateway", "method", "deterministic", "omitted_turn_count", 1, "text", "older fact")));
        require(original.size() == 1 && summaryAdapter.requests.get(0).messages().size() == 2, "derived summary altered saved messages");
        require(String.valueOf(summaryAdapter.requests.get(0).messages().get(0).content().get(0).get("text")).contains("Gateway-derived context"), "derived summary was not labelled");
        Map<?, ?> futureTool = (Map<?, ?>) Json.parse("{\"name\":\"read\",\"description\":\"Read\",\"parameters\":{},\"source\":{\"id\":\"service-1\",\"kind\":\"future-protocol\"},\"visibility\":\"always-send\",\"extensions\":{\"future\":\"ignored\"}}");
        ToolDefinition definition = ToolDefinition.fromCanonical(futureTool);
        require(definition.source().get("id").equals("service-1") && definition.source().get("kind").equals("future-protocol"), "new source label or identity was lost");
        var rejected = new AgentLoop(new RecordingAdapter(), List.of()).run(new AgentLoopRequest(List.of(), new AgentLoopRequestMetadata("corr", "", "message", Map.of()), "mock", "local", Map.of("actor", Map.of("id", "owner"), "correlation_id", "corr", "unexpected", "spoof"), null));
        require(rejected.size() == 1 && "auth_error".equals(rejected.get(0).data().get("code")), "unknown auth context field was accepted");
        System.out.println("Current contract tests passed.");
    }
}
