package com.mikrotikmanager.gateway.routeros;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small loopback-only RouterOS REST fake for gateway integration tests.
 *
 * <p>It intentionally records only request method and path. In particular it
 * never reads, stores, exposes, or asserts HTTP headers such as Authorization.
 */
final class FakeRouterOsServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, FakeResponse> responses = new ConcurrentHashMap<>();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    private FakeRouterOsServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::handle);
    }

    static FakeRouterOsServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeRouterOsServer fake = new FakeRouterOsServer(server);
            server.start();
            return fake;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start loopback Fake RouterOS server.", exception);
        }
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void respondJson(String path, String body) {
        respond(path, 200, body);
    }

    void respond(String path, int status, String body) {
        responses.put(path, new FakeResponse(status, body));
    }

    List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    long requestCount(String path) {
        return requests.stream().filter(request -> path.equals(request.path())).count();
    }

    void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // Do not inspect request headers: Basic credentials are intentionally
        // outside the observable test surface of this fake.
        requests.add(new CapturedRequest(exchange.getRequestMethod(), path));

        FakeResponse response = responses.getOrDefault(path, new FakeResponse(404, "{\"error\":404}"));
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    record CapturedRequest(String method, String path) {
    }

    private record FakeResponse(int status, String body) {
    }
}
