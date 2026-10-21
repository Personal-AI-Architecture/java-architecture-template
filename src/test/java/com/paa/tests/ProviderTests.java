package com.paa.tests;

import com.paa.adapters.OpenAiCompatibleAdapter;
import com.paa.types.CompletionRequest;
import com.paa.types.Message;
import com.paa.types.ModelStreamChunk;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ProviderTests {
    private ProviderTests() {}
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> { if (!exchange.getRequestMethod().equals("POST")) throw new AssertionError("provider request was not POST"); String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); if (!request.contains("\"stream\":true")) throw new AssertionError("provider request did not request streaming"); byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); }); server.start();
        try { OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "test-model"); List<ModelStreamChunk> chunks = adapter.stream(new CompletionRequest(List.of(new Message("user", "hello")), List.of(), true, "", 0, 0)); if (chunks.size() != 2 || !chunks.get(0).delta().get("content").equals("ok") || !chunks.get(1).delta().get("finish_reason").equals("stop")) throw new AssertionError("provider SSE was not normalized"); System.out.println("Provider adapter tests passed."); } finally { server.stop(0); }
    }
}
