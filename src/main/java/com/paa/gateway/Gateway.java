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
import java.util.function.Supplier;

public final class Gateway {
    public record Response(int status, Map<String, String> headers, String body, List<StreamEvent> stream) {}
    private record StoredMessage(String id, Message message, String createdAt) {}
    private static final class Conversation { private final String id; private final String createdAt; private String updatedAt; private final List<StoredMessage> messages = new ArrayList<>(); private Conversation(String id, String now) { this.id = id; this.createdAt = now; this.updatedAt = now; } }
    private static final AtomicLong IDS = new AtomicLong();
    private final AgentLoop engine;
    private final MemoryStore memory;
    private final Supplier<String> currentModel;
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();

    public Gateway(AgentLoop engine, MemoryStore memory) { this(engine, memory, () -> "mock"); }
    public Gateway(AgentLoop engine, MemoryStore memory, Supplier<String> currentModel) { this.engine = engine; this.memory = memory == null ? new MemoryStore(".paa-memory") : memory; this.currentModel = currentModel; }
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
        Object parsed;
        try { parsed = Json.parse(body == null ? "" : body); }
        catch (IllegalArgumentException error) { return error(400, "invalid_request", "Malformed JSON request."); }
        if (!(parsed instanceof Map<?, ?> requestBody) || requestBody.keySet().stream().anyMatch(key -> !List.of("content", "metadata").contains(key))) return error(400, "invalid_request", "Request must contain content and optional metadata.");
        if (!(requestBody.get("content") instanceof List<?> rawParts) || rawParts.isEmpty()) return error(400, "invalid_request", "Content must be a non-empty array of parts.");
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Object item : rawParts) {
            if (!(item instanceof Map<?, ?> rawPart) || !(rawPart.get("type") instanceof String type)) return error(400, "invalid_request", "Invalid content part.");
            if (type.equals("text") && !(rawPart.get("text") instanceof String)) return error(400, "invalid_request", "Invalid text part.");
            if ((type.equals("image") || type.equals("file")) && !(rawPart.get("url") instanceof String)) return error(400, "invalid_request", "Invalid media part.");
            if (!List.of("text", "image", "file").contains(type)) return error(400, "invalid_request", "Invalid content part type.");
            Map<String, Object> part = new LinkedHashMap<>(); rawPart.forEach((key, value) -> part.put(String.valueOf(key), value)); parts.add(part);
        }
        if (requestBody.containsKey("metadata") && !(requestBody.get("metadata") instanceof Map<?, ?>)) return error(400, "invalid_request", "Metadata must be an object.");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (requestBody.get("metadata") instanceof Map<?, ?> rawMetadata) rawMetadata.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        String actor = header(headers, "X-Actor-ID"), permissions = header(headers, "X-Actor-Permissions");
        if (actor.isBlank() || permissions.isBlank()) return error(401, "missing_actor_headers", "Missing required actor headers.");
        String conversationId = requestedId.isBlank() ? id("conv") : requestedId;
        Conversation conversation = conversations.get(conversationId);
        if (!requestedId.isBlank() && conversation == null) return error(404, "conversation_not_found", "Conversation was not found.");
        if (conversation == null) { conversation = new Conversation(conversationId, timestamp()); conversations.put(conversationId, conversation); }
        append(conversation, new Message("user", parts));
        String correlation = metadata.get("correlation_id") instanceof String value && !value.isBlank() ? value : id("corr");
        Map<String, Object> identity = Map.of("id", actor, "type", "owner", "display_name", actor, "status", "active", "created", timestamp());
        Map<String, Object> authContext = Map.of("actor", identity, "authentication_id", id("authn"), "authenticated_at", timestamp(), "correlation_id", correlation);
        List<Message> messages = conversation.messages.stream().map(StoredMessage::message).toList();
        EngineRequest request = new EngineRequest(messages, new EngineRequestMetadata(correlation, conversationId, "message", metadata), currentModel.get(), authContext);
        List<StreamEvent> stream = new ArrayList<>(engine.run(request));
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < stream.size(); index++) {
            StreamEvent event = stream.get(index); Map<String, Object> data = new LinkedHashMap<>(event.data());
            if (event.event().equals("text-delta")) text.append(data.getOrDefault("content", ""));
            if (event.event().equals("done")) { data.putIfAbsent("message_id", id("msg")); data.putIfAbsent("finish_reason", "stop"); }
            data.put("_event_type", event.event()); stream.set(index, new StreamEvent(event.event(), data));
        }
        if (!text.isEmpty()) append(conversation, new Message("assistant", text.toString()));
        return new Response(200, Map.of("Content-Type", "text/event-stream", "Cache-Control", "no-cache", "Connection", "keep-alive", "X-Conversation-ID", conversationId), "", stream);
    }
    private void append(Conversation conversation, Message message) { String now = timestamp(), messageId = id("msg"); conversation.messages.add(new StoredMessage(messageId, message, now)); conversation.updatedAt = now; memory.record(new MemoryStore.Entry("message", messageId, conversation.id, message.role(), message.content())); }
    private Response list() { List<Map<String, Object>> items = conversations.values().stream().map(conversation -> Map.<String, Object>of("id", conversation.id, "created_at", conversation.createdAt, "updated_at", conversation.updatedAt, "message_count", conversation.messages.size())).toList(); return json(200, Json.stringify(Map.of("conversations", items, "total", items.size()))); }
    private Response detail(String id) { Conversation conversation = conversations.get(id); if (conversation == null) return error(404, "conversation_not_found", "Conversation was not found."); List<Map<String, Object>> messages = conversation.messages.stream().map(stored -> Map.<String, Object>of("id", stored.id(), "role", stored.message().role(), "content", stored.message().content(), "created_at", stored.createdAt())).toList(); return json(200, Json.stringify(Map.of("id", id, "created_at", conversation.createdAt, "updated_at", conversation.updatedAt, "messages", messages))); }
    private static Response json(int status, String body) { return new Response(status, Map.of("Content-Type", "application/json"), body, List.of()); }
    private static Response error(int status, String code, String message) { return json(status, Json.stringify(Map.of("code", code, "message", message))); }
    private static String header(Map<String, String> headers, String name) { return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse("").trim(); }
    private static String timestamp() { return java.time.Instant.now().toString(); }
    private static String id(String prefix) { return prefix + "_" + IDS.incrementAndGet(); }
}
