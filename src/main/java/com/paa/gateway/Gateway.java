package com.paa.gateway;

import com.paa.engine.AgentLoop;
import com.paa.memory.MemoryStore;
import com.paa.types.EngineRequest;
import com.paa.types.ContextBudget;
import com.paa.types.EngineRequestMetadata;
import com.paa.types.Message;
import com.paa.types.StreamEvent;
import java.util.ArrayList;
import java.net.URLDecoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class Gateway {
    public record Response(int status, Map<String, String> headers, String body, List<StreamEvent> stream) {}
    private record StoredMessage(String id, Message message, String createdAt, String model, String provider) {}
    private static final class Conversation { private final String id; private final String createdAt; private String updatedAt; private final List<StoredMessage> messages = new ArrayList<>(); private Conversation(String id, String now) { this.id = id; this.createdAt = now; this.updatedAt = now; } }
    private static final AtomicLong IDS = new AtomicLong();
    private final AgentLoop engine;
    private final MemoryStore memory;
    private final Supplier<String> currentModel;
    private final Supplier<String> currentProvider;
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();
    private Path persistenceFile;

    public synchronized void enablePersistence(String root) throws IOException {
        Path directory = Path.of(root);
        Files.createDirectories(directory);
        persistenceFile = directory.resolve("conversations.json");
        if (!Files.exists(persistenceFile)) return;
        Object parsed = Json.parse(Files.readString(persistenceFile));
        if (!(parsed instanceof Map<?, ?> saved)) throw new IOException("Invalid conversation memory.");
        for (Object value : saved.values()) {
            if (!(value instanceof Map<?, ?> item)) throw new IOException("Invalid conversation record.");
            String id = String.valueOf(item.get("id"));
            Conversation conversation = new Conversation(id, String.valueOf(item.get("created_at")));
            conversation.updatedAt = String.valueOf(item.get("updated_at"));
            if (item.get("messages") instanceof List<?> messages) for (Object messageValue : messages) {
                if (!(messageValue instanceof Map<?, ?> entry) || !(entry.get("content") instanceof List<?> rawContent)) throw new IOException("Invalid saved message.");
                List<Map<String, Object>> content = new ArrayList<>();
                for (Object part : rawContent) {
                    if (!(part instanceof Map<?, ?> rawPart)) throw new IOException("Invalid saved content part.");
                    Map<String, Object> fields = new LinkedHashMap<>();
                    rawPart.forEach((key, field) -> fields.put(String.valueOf(key), field));
                    content.add(fields);
                }
                conversation.messages.add(new StoredMessage(String.valueOf(entry.get("id")), new Message(String.valueOf(entry.get("role")), content),
                    String.valueOf(entry.get("created_at")), String.valueOf(entry.get("model")), String.valueOf(entry.get("provider"))));
            }
            conversations.put(id, conversation);
        }
    }

    private void save() throws IOException {
        if (persistenceFile == null) return;
        Map<String, Object> saved = new LinkedHashMap<>();
        for (Conversation conversation : conversations.values()) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (StoredMessage entry : conversation.messages) messages.add(Map.of("id", entry.id(), "role", entry.message().role(),
                "content", entry.message().content(), "created_at", entry.createdAt(), "model", entry.model(), "provider", entry.provider()));
            saved.put(conversation.id, Map.of("id", conversation.id, "created_at", conversation.createdAt,
                "updated_at", conversation.updatedAt, "messages", messages));
        }
        Path temporary = persistenceFile.resolveSibling("conversations.tmp");
        Files.writeString(temporary, Json.stringify(saved));
        Files.move(temporary, persistenceFile, StandardCopyOption.REPLACE_EXISTING);
    }

    public Gateway(AgentLoop engine, MemoryStore memory) { this(engine, memory, () -> "mock", () -> "local"); }
    public Gateway(AgentLoop engine, MemoryStore memory, Supplier<String> currentModel) { this(engine, memory, currentModel, () -> "local"); }
    public Gateway(AgentLoop engine, MemoryStore memory, Supplier<String> currentModel, Supplier<String> currentProvider) { this.engine = engine; this.memory = memory == null ? new MemoryStore(".paa-memory") : memory; this.currentModel = currentModel; this.currentProvider = currentProvider; }
    public synchronized Response handle(String method, String path, String body, Map<String, String> headers) {
        String route = path.split("\\?", 2)[0];
        if (method.equalsIgnoreCase("GET") && route.equals("/health")) return json(200, "{\"status\":\"ok\",\"architecture\":\"PAA\"}");
        if (method.equalsIgnoreCase("GET") && route.equals("/conversations")) return list(path);
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
            List<String> allowed = type.equals("text") ? List.of("type", "text", "extensions") : List.of("type", "url", "name", "mime_type", "extensions");
            if (rawPart.keySet().stream().anyMatch(key -> !allowed.contains(key)) || (rawPart.containsKey("extensions") && !(rawPart.get("extensions") instanceof Map<?, ?>))) return error(400, "invalid_request", "Invalid content part fields.");
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
        String model = currentModel.get(), provider = currentProvider.get();
        int limit = ContextBudget.limit(engine.contextLimits(), provider, model);
        if (limit <= 0) return error(503, "context_limit_unknown", "No safe context limit for selected provider and model.");
        ContextBudget.Selection selection = ContextBudget.selectCompleteTurns(messages, engine.tools(), limit);
        if (selection == null) return error(422, "context_overflow", "Current message exceeds the selected model context window.");
        EngineRequest request = new EngineRequest(selection.messages(), new EngineRequestMetadata(correlation, conversationId, "message", metadata), model, provider, authContext, null);
        List<StreamEvent> stream = new ArrayList<>(engine.run(request));
        if (selection.omittedTurns() > 0) stream.add(0, new StreamEvent("context_notice", Map.of("_event_type", "context_notice", "action", "omitted", "omitted_turn_count", selection.omittedTurns(), "message", "Older conversation turns were omitted from this reply's working context.")));
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < stream.size(); index++) {
            StreamEvent event = stream.get(index); Map<String, Object> data = new LinkedHashMap<>(event.data());
            if (event.event().equals("text-delta")) text.append(data.getOrDefault("content", ""));
            if (event.event().equals("done")) { data.putIfAbsent("finish_reason", "stop"); }
            data.put("_event_type", event.event()); stream.set(index, new StreamEvent(event.event(), data));
        }
        if (stream.stream().anyMatch(event -> event.event().equals("done"))) {
            String replyId = append(conversation, new Message("assistant", text.toString()), model, provider);
            for (int index = 0; index < stream.size(); index++) if (stream.get(index).event().equals("done")) {
                StreamEvent event = stream.get(index);
                Map<String, Object> data = new LinkedHashMap<>(event.data());
                data.put("message_id", replyId);
                stream.set(index, new StreamEvent("done", data));
            }
        }
        try { save(); } catch (IOException error) { return error(500, "storage_error", "Conversation could not be saved."); }
        return new Response(200, Map.of("Content-Type", "text/event-stream", "Cache-Control", "no-cache", "Connection", "keep-alive", "X-Conversation-ID", conversationId), "", stream);
    }
    private String append(Conversation conversation, Message message) { return append(conversation, message, "", ""); }
    private String append(Conversation conversation, Message message, String model, String provider) { if (message.role().equals("assistant") && (model.isBlank() || provider.isBlank())) throw new IllegalArgumentException("Assistant reply requires model and provider"); String now = timestamp(), messageId = id("msg"); conversation.messages.add(new StoredMessage(messageId, message, now, model, provider)); conversation.updatedAt = now; memory.record(new MemoryStore.Entry("message", messageId, conversation.id, message.role(), message.content(), model, provider)); return messageId; }
    private Response list(String path) {
        Map<String, String> query = new LinkedHashMap<>();
        if (path.contains("?")) for (String part : path.substring(path.indexOf('?') + 1).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        int limit, offset;
        Instant after = null, before = null;
        try {
            limit = Integer.parseInt(query.getOrDefault("limit", "20"));
            offset = Integer.parseInt(query.getOrDefault("offset", "0"));
            if (limit < 1 || limit > 100 || offset < 0) throw new IllegalArgumentException();
            if (query.containsKey("from")) after = Instant.parse(query.get("from"));
            if (query.containsKey("to")) before = Instant.parse(query.get("to"));
        } catch (Exception error) { return error(400, "invalid_request", "Invalid conversation filter."); }
        String search = query.getOrDefault("search", "").toLowerCase();
        Instant fromBound = after, toBound = before;
        List<Conversation> matches = conversations.values().stream()
            .filter(item -> fromBound == null || !Instant.parse(item.createdAt).isBefore(fromBound))
            .filter(item -> toBound == null || !Instant.parse(item.createdAt).isAfter(toBound))
            .filter(item -> search.isEmpty() || item.messages.stream().anyMatch(message -> message.message().content().toString().toLowerCase().contains(search)))
            .sorted(Comparator.comparing((Conversation item) -> item.updatedAt).reversed())
            .toList();
        List<Map<String, Object>> items = matches.stream().skip(offset).limit(limit)
            .map(item -> Map.<String, Object>of("id", item.id, "created_at", item.createdAt, "updated_at", item.updatedAt, "message_count", item.messages.size()))
            .toList();
        return json(200, Json.stringify(Map.of("conversations", items, "total", matches.size(), "limit", limit, "offset", offset)));
    }
    private Response detail(String id) { Conversation conversation = conversations.get(id); if (conversation == null) return error(404, "conversation_not_found", "Conversation was not found."); List<Map<String, Object>> messages = conversation.messages.stream().map(stored -> { Map<String, Object> item = new LinkedHashMap<>(); item.put("id", stored.id()); item.put("role", stored.message().role()); item.put("content", stored.message().content()); item.put("created_at", stored.createdAt()); if (stored.message().role().equals("assistant")) { item.put("model", stored.model()); item.put("provider", stored.provider()); } return item; }).toList(); return json(200, Json.stringify(Map.of("id", id, "created_at", conversation.createdAt, "updated_at", conversation.updatedAt, "messages", messages))); }
    private static Response json(int status, String body) { return new Response(status, Map.of("Content-Type", "application/json"), body, List.of()); }
    private static Response error(int status, String code, String message) { return json(status, Json.stringify(Map.of("code", code, "message", message))); }
    private static String header(Map<String, String> headers, String name) { return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse("").trim(); }
    private static String timestamp() { return java.time.Instant.now().toString(); }
    private static String id(String prefix) { return prefix + "_" + IDS.incrementAndGet(); }
}
