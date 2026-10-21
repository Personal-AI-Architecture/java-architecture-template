package com.paa.gateway;

import com.paa.engine.AgentLoop;
import com.paa.memory.MemoryStore;
import com.paa.types.EngineRequest;
import com.paa.types.EngineRequestMetadata;
import com.paa.types.Message;
import com.paa.types.StreamEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class Gateway {
    public record Response(int status, Map<String, String> headers, String body, List<StreamEvent> stream) {}
    private record StoredMessage(String id, Message message, String createdAt) {}
    private static final class Conversation { private final String id; private final String createdAt; private String updatedAt; private final List<StoredMessage> messages = new ArrayList<>(); private Conversation(String id, String now) { this.id = id; this.createdAt = now; this.updatedAt = now; } }
    private static final AtomicLong IDS = new AtomicLong();
    private final AgentLoop engine;
    private final MemoryStore memory;
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();

    public Gateway(AgentLoop engine, MemoryStore memory) { this.engine = engine; this.memory = memory == null ? new MemoryStore(".paa-memory") : memory; }
    public synchronized Response handle(String method, String path, String body, Map<String, String> headers) {
        String route = path.split("\\?", 2)[0];
        if (method.equalsIgnoreCase("GET") && route.equals("/health")) return json(200, "{\"status\":\"ok\",\"architecture\":\"PAA\"}");
        if (method.equalsIgnoreCase("GET") && route.equals("/conversations")) return list();
        if (method.equalsIgnoreCase("GET") && route.startsWith("/conversations/")) return detail(route.substring("/conversations/".length()));
        if (method.equalsIgnoreCase("POST") && (route.equals("/conversations/messages") || route.equals("/chat"))) return message(body, headers, "");
        if (method.equalsIgnoreCase("POST") && route.startsWith("/conversations/") && route.endsWith("/messages")) return message(body, headers, route.substring("/conversations/".length(), route.length() - "/messages".length()).replace("/", ""));
        return error(404, "not_found", "Route not found.");
    }
    private Response message(String body, Map<String, String> headers, String requestedId) {
        if (body == null || body.contains("\"messages\"") || !body.contains("\"content\"") || Json.stringField(body, "content").isBlank()) return error(400, "invalid_request", "Request must contain non-empty content and optional metadata.");
        String actor = header(headers, "X-Actor-ID"), permissions = header(headers, "X-Actor-Permissions"); if (actor.isBlank() || permissions.isBlank()) return error(401, "missing_actor_headers", "Missing required actor headers.");
        String conversationId = requestedId.isBlank() ? id("conv") : requestedId; Conversation conversation = conversations.get(conversationId); if (!requestedId.isBlank() && conversation == null) return error(404, "conversation_not_found", "Conversation was not found."); if (conversation == null) { conversation = new Conversation(conversationId, timestamp()); conversations.put(conversationId, conversation); }
        append(conversation, new Message("user", Json.stringField(body, "content"))); String correlation = Json.stringField(body, "correlation_id"); if (correlation.isBlank()) correlation = id("corr");
        List<Message> messages = conversation.messages.stream().map(StoredMessage::message).toList(); EngineRequest request = new EngineRequest(messages, new EngineRequestMetadata(correlation, conversationId, "", Map.of(), actor, List.of(permissions.split(",")), List.of())); List<StreamEvent> stream = new ArrayList<>(engine.run(request, headers));
        StringBuilder text = new StringBuilder(); for (StreamEvent event : stream) if (event.event().equals("text-delta")) text.append(event.data().getOrDefault("text", "")); for (int index = 0; index < stream.size(); index++) if (stream.get(index).event().equals("done")) { Map<String, Object> data = new LinkedHashMap<>(stream.get(index).data()); data.putIfAbsent("message_id", id("msg")); data.putIfAbsent("finish_reason", "stop"); stream.set(index, new StreamEvent("done", data)); } if (!text.isEmpty()) append(conversation, new Message("assistant", text.toString()));
        return new Response(200, Map.of("Content-Type", "text/event-stream", "Cache-Control", "no-cache", "Connection", "keep-alive", "X-Conversation-ID", conversationId), "", stream);
    }
    private void append(Conversation conversation, Message message) { String now = timestamp(), messageId = id("msg"); conversation.messages.add(new StoredMessage(messageId, message, now)); conversation.updatedAt = now; memory.record(new MemoryStore.Entry("message", messageId, conversation.id, message.role(), message.content())); }
    private Response list() { List<Map<String, Object>> items = conversations.values().stream().map(conversation -> Map.<String, Object>of("id", conversation.id, "created_at", conversation.createdAt, "updated_at", conversation.updatedAt, "message_count", conversation.messages.size())).toList(); return json(200, Json.stringify(Map.of("conversations", items, "total", items.size()))); }
    private Response detail(String id) { Conversation conversation = conversations.get(id); if (conversation == null) return error(404, "conversation_not_found", "Conversation was not found."); List<Map<String, Object>> messages = conversation.messages.stream().map(stored -> Map.<String, Object>of("id", stored.id(), "role", stored.message().role(), "content", stored.message().content(), "created_at", stored.createdAt())).toList(); return json(200, Json.stringify(Map.of("id", id, "created_at", conversation.createdAt, "updated_at", conversation.updatedAt, "messages", messages))); }
    private static Response json(int status, String body) { return new Response(status, Map.of("Content-Type", "application/json"), body, List.of()); }
    private static Response error(int status, String code, String message) { return json(status, Json.stringify(Map.of("error", Map.of("code", code, "message", message)))); }
    private static String header(Map<String, String> headers, String name) { return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse("").trim(); }
    private static String timestamp() { return Long.toString(System.currentTimeMillis()); }
    private static String id(String prefix) { return prefix + "_" + IDS.incrementAndGet(); }
}
