package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small loopback-only RouterOS REST fake for gateway integration tests.
 *
 * <p>The legacy request journal records only method and path. The opt-in write
 * journal additionally records a sanitized JSON body. In particular this fake
 * never reads, stores, exposes, or asserts HTTP headers such as Authorization.
 */
final class FakeRouterOsServer implements AutoCloseable {
    private static final String FIREWALL_FILTER_PATH = "/rest/ip/firewall/filter";
    private static final String FIREWALL_FILTER_ITEM_PREFIX = FIREWALL_FILTER_PATH + "/";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String REDACTED = "[REDACTED]";

    private final HttpServer server;
    private final Map<String, FakeResponse> responses = new ConcurrentHashMap<>();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final List<CapturedRequest> readJournal = new CopyOnWriteArrayList<>();
    private final List<CapturedWrite> writeJournal = new CopyOnWriteArrayList<>();
    private final Object firewallStateMonitor = new Object();
    private volatile StatefulFirewallFilters firewallFilters;

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
        if (FIREWALL_FILTER_PATH.equals(path) && firewallFilters != null) {
            seedFirewallFilters(body);
            return;
        }
        respond(path, 200, body);
    }

    void respond(String path, int status, String body) {
        responses.put(path, new FakeResponse(status, body));
    }

    /**
     * Enables the stateful contract for the firewall filter collection only.
     * All other paths retain the original static-response behavior.
     */
    FakeRouterOsServer enableStatefulFirewallFilters() {
        synchronized (firewallStateMonitor) {
            if (firewallFilters != null) {
                return this;
            }

            StatefulFirewallFilters state = new StatefulFirewallFilters();
            FakeResponse configuredResponse = responses.get(FIREWALL_FILTER_PATH);
            if (configuredResponse != null && configuredResponse.status() == 200) {
                state.replace(parseSeed(configuredResponse.body()));
                responses.remove(FIREWALL_FILTER_PATH);
            }
            firewallFilters = state;
        }
        return this;
    }

    /** Seeds the opt-in state with a RouterOS collection JSON response. */
    FakeRouterOsServer seedFirewallFilters(String body) {
        enableStatefulFirewallFilters();
        synchronized (firewallStateMonitor) {
            firewallFilters.replace(parseSeed(body));
            responses.remove(FIREWALL_FILTER_PATH);
        }
        return this;
    }

    List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    List<CapturedRequest> readRequests() {
        return List.copyOf(readJournal);
    }

    List<CapturedWrite> writeRequests() {
        return List.copyOf(writeJournal);
    }

    List<CapturedRequest> readJournal() {
        return readRequests();
    }

    List<CapturedWrite> writeJournal() {
        return writeRequests();
    }

    long requestCount(String path) {
        return requests.stream().filter(request -> path.equals(request.path())).count();
    }

    void clearRequests() {
        requests.clear();
        readJournal.clear();
        writeJournal.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        // Do not inspect request headers: Basic credentials are intentionally
        // outside the observable test surface of this fake.
        String requestBody = "GET".equals(method)
                ? ""
                : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        recordRequest(method, path, requestBody);

        FakeResponse response = configuredResponse(path);
        if (response == null && firewallFilters != null && isFirewallFilterResource(path)) {
            response = handleStatefulFirewallFilter(path, method, requestBody);
        }
        if (response == null) {
            response = new FakeResponse(404, "{\"error\":404}");
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void recordRequest(String method, String path, String body) {
        CapturedRequest request = new CapturedRequest(method, path);
        requests.add(request);
        if ("GET".equals(method)) {
            readJournal.add(request);
            return;
        }
        writeJournal.add(new CapturedWrite(method, path, sanitizeBody(body)));
    }

    private FakeResponse configuredResponse(String path) {
        FakeResponse response = responses.get(path);
        if (response == null && firewallFilters != null && isFirewallFilterResource(path)) {
            response = responses.get(FIREWALL_FILTER_PATH);
        }
        return response;
    }

    private FakeResponse handleStatefulFirewallFilter(String path, String method, String body) {
        synchronized (firewallStateMonitor) {
            if (FIREWALL_FILTER_PATH.equals(path)) {
                if ("GET".equals(method)) {
                    return jsonResponse(200, firewallFilters.snapshot());
                }
                if ("PUT".equals(method)) {
                    return addFirewallFilter(body);
                }
                return errorResponse(405);
            }

            if (path.startsWith(FIREWALL_FILTER_ITEM_PREFIX)) {
                if ("DELETE".equals(method)) {
                    return deleteFirewallFilter(path.substring(FIREWALL_FILTER_ITEM_PREFIX.length()));
                }
                if ("POST".equals(method) || "PATCH".equals(method)) {
                    return errorResponse(405);
                }
            }
            return errorResponse(404);
        }
    }

    private FakeResponse addFirewallFilter(String body) {
        JsonNode parsed;
        try {
            parsed = JSON_MAPPER.readTree(body);
        } catch (IOException exception) {
            return errorResponse(400);
        }
        if (!(parsed instanceof ObjectNode object)) {
            return errorResponse(400);
        }

        JsonNode sanitized = sanitizeNode(object);
        if (!(sanitized instanceof ObjectNode filter)) {
            return errorResponse(400);
        }

        int insertionIndex = firewallFilters.size();
        JsonNode placeBefore = filter.get("place-before");
        if (placeBefore != null) {
            if (!placeBefore.isTextual() || placeBefore.textValue().isBlank()) {
                return errorResponse(400);
            }
            insertionIndex = "0".equals(placeBefore.textValue())
                    ? 0
                    : firewallFilters.indexOf(placeBefore.textValue());
            if (insertionIndex < 0) {
                // An explicit but unknown place-before must never degrade into
                // an append operation: the caller asked for a precise order.
                return errorResponse(404);
            }
        }

        String callerSuppliedId = StatefulFirewallFilters.textId(filter.get(".id"));
        filter.remove(".id");
        filter.remove("place-before");
        String generatedId = firewallFilters.nextGeneratedId(callerSuppliedId);
        filter.put(".id", generatedId);
        firewallFilters.insert(insertionIndex, filter);

        ObjectNode response = JSON_MAPPER.createObjectNode();
        response.put("ret", generatedId);
        return jsonResponse(200, response);
    }

    private FakeResponse deleteFirewallFilter(String id) {
        if (id.isEmpty() || id.contains("/")) {
            return errorResponse(404);
        }
        int index = firewallFilters.indexOf(id);
        if (index < 0) {
            return errorResponse(404);
        }
        firewallFilters.remove(index);

        ObjectNode response = JSON_MAPPER.createObjectNode();
        response.put("ret", id);
        return jsonResponse(200, response);
    }

    private static boolean isFirewallFilterResource(String path) {
        return FIREWALL_FILTER_PATH.equals(path) || path.startsWith(FIREWALL_FILTER_ITEM_PREFIX);
    }

    private static FakeResponse jsonResponse(int status, JsonNode body) {
        try {
            return new FakeResponse(status, JSON_MAPPER.writeValueAsString(body));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize fake RouterOS response.", exception);
        }
    }

    private static FakeResponse errorResponse(int status) {
        return new FakeResponse(status, "{\"error\":" + status + "}");
    }

    private static List<ObjectNode> parseSeed(String body) {
        try {
            JsonNode parsed = JSON_MAPPER.readTree(body);
            if (!(parsed instanceof ArrayNode array)) {
                throw new IllegalArgumentException("Stateful firewall seed must be a JSON array.");
            }

            List<ObjectNode> seed = new ArrayList<>();
            for (JsonNode item : array) {
                if (!(item instanceof ObjectNode object)) {
                    throw new IllegalArgumentException("Stateful firewall seed items must be JSON objects.");
                }
                ObjectNode filter = (ObjectNode) sanitizeNode(object);
                filter.remove("place-before");
                seed.add(filter);
            }
            return seed;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Stateful firewall seed must be valid JSON.", exception);
        }
    }

    private static String sanitizeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode parsed = JSON_MAPPER.readTree(body);
            if (parsed == null) {
                return "";
            }
            JsonNode sanitized = parsed.isTextual()
                    ? JSON_MAPPER.getNodeFactory().textNode(REDACTED)
                    : sanitizeNode(parsed);
            return JSON_MAPPER.writeValueAsString(sanitized);
        } catch (IOException exception) {
            // Do not retain an unparseable raw body: it may contain credentials.
            return "<invalid-json>";
        }
    }

    private static JsonNode sanitizeNode(JsonNode node) {
        if (node == null) {
            return JSON_MAPPER.nullNode();
        }
        if (node instanceof ObjectNode object) {
            ObjectNode sanitized = JSON_MAPPER.createObjectNode();
            object.fields().forEachRemaining(entry -> sanitized.set(
                    entry.getKey(), isSensitiveField(entry.getKey())
                            ? JSON_MAPPER.getNodeFactory().textNode(REDACTED)
                            : sanitizeNode(entry.getValue())
            ));
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = JSON_MAPPER.createArrayNode();
            node.forEach(item -> sanitized.add(sanitizeNode(item)));
            return sanitized;
        }
        return node.deepCopy();
    }

    private static boolean isSensitiveField(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("authorization")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("apikey");
    }

    record CapturedRequest(String method, String path) {
    }

    record CapturedWrite(String method, String path, String body) {
    }

    private static final class StatefulFirewallFilters {
        private final List<ObjectNode> filters = new ArrayList<>();
        private int nextId = 1;

        private void replace(List<ObjectNode> seed) {
            filters.clear();
            for (ObjectNode filter : seed) {
                ObjectNode copy = filter.deepCopy();
                String id = textId(copy.get(".id"));
                if (id == null || contains(id)) {
                    copy.put(".id", nextGeneratedId(null));
                }
                filters.add(copy);
            }
        }

        private ArrayNode snapshot() {
            ArrayNode snapshot = JSON_MAPPER.createArrayNode();
            filters.forEach(filter -> snapshot.add(filter.deepCopy()));
            return snapshot;
        }

        private int size() {
            return filters.size();
        }

        private int indexOf(String id) {
            for (int index = 0; index < filters.size(); index++) {
                if (id.equals(textId(filters.get(index).get(".id")))) {
                    return index;
                }
            }
            return -1;
        }

        private void insert(int index, ObjectNode filter) {
            filters.add(index, filter);
        }

        private void remove(int index) {
            filters.remove(index);
        }

        private String nextGeneratedId(String excludedId) {
            String id;
            do {
                id = "*FAKE" + nextId++;
            } while (id.equals(excludedId) || contains(id));
            return id;
        }

        private boolean contains(String id) {
            return indexOf(id) >= 0;
        }

        private static String textId(JsonNode id) {
            return id != null && id.isTextual() && !id.textValue().isBlank() ? id.textValue() : null;
        }
    }

    private record FakeResponse(int status, String body) {
    }
}
