package com.paa;

import com.paa.adapters.AdapterLoader;
import com.paa.adapters.OpenAiCompatibleAdapter;
import com.paa.engine.AgentLoop;
import com.paa.gateway.Gateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PaaServer {
    private PaaServer() {}
    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "127.0.0.1"; int port = args.length > 1 ? Integer.parseInt(args[1]) : 8000;
        Gateway gateway = new Gateway(new AgentLoop(AdapterLoader.load("mock", new OpenAiCompatibleAdapter("", "", "")), java.util.List.of()), null);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0); server.createContext("/", exchange -> handle(exchange, gateway)); server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); server.start();
        System.out.println("PAA Java template listening at http://" + host + ":" + port); System.out.println("Try: curl http://" + host + ":" + port + "/health");
    }
    private static void handle(HttpExchange exchange, Gateway gateway) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); Map<String, String> headers = new LinkedHashMap<>(); exchange.getRequestHeaders().forEach((key, values) -> { if (!values.isEmpty()) headers.put(key, values.get(0)); }); Gateway.Response response = gateway.handle(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body, headers); boolean stream = response.headers().getOrDefault("Content-Type", "").equals("text/event-stream"); String output = stream ? response.stream().stream().map(event -> "event: " + event.event() + "\ndata: " + com.paa.gateway.Json.stringify(event.data()) + "\n\n").reduce("", String::concat) : response.body(); response.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value)); byte[] bytes = output.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length)); exchange.sendResponseHeaders(response.status(), bytes.length); try (OutputStream target = exchange.getResponseBody()) { target.write(bytes); }
    }
}
