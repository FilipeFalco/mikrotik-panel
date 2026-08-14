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
import java.util.Base64;
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
    private static final String SIMPLE_QUEUE_PATH = "/rest/queue/simple";
    private static final String SIMPLE_QUEUE_ITEM_PREFIX = SIMPLE_QUEUE_PATH + "/";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String REDACTED = "[REDACTED]";

    private final HttpServer server;
    private final Map<String, FakeResponse> responses = new ConcurrentHashMap<>();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final List<CapturedRequest> readJournal = new CopyOnWriteArrayList<>();
    private final List<CapturedWrite> writeJournal = new CopyOnWriteArrayList<>();
    private final Object firewallStateMonitor = new Object();
    private volatile StatefulFirewallFilters firewallFilters;
    private volatile StatefulSimpleQueues simpleQueues;
    private volatile Credentials credentials;

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
        if (SIMPLE_QUEUE_PATH.equals(path) && simpleQueues != null) { seedSimpleQueues(body); return; }
        respond(path, 200, body);
    }

    void respond(String path, int status, String body) {
        responses.put(path, new FakeResponse(status, body));
    }

    /** Requires distinct principals for GET and the two allow-listed writes. */
    FakeRouterOsServer requireCredentials(String readUsername, String readPassword,
                                          String writeUsername, String writePassword) {
        credentials = new Credentials(readUsername, readPassword, writeUsername, writePassword);
        return this;
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

    /** Stateful ordered /queue/simple fixture with name and parent validation. */
    FakeRouterOsServer enableStatefulSimpleQueues() {
        if (simpleQueues != null) return this;
        synchronized (firewallStateMonitor) {
            if (simpleQueues == null) { simpleQueues = new StatefulSimpleQueues(); FakeResponse response = responses.get(SIMPLE_QUEUE_PATH);
                if (response != null && response.status() == 200) { simpleQueues.replace(parseSeed(response.body())); responses.remove(SIMPLE_QUEUE_PATH); } }
        }
        return this;
    }
    FakeRouterOsServer seedSimpleQueues(String body) { enableStatefulSimpleQueues(); synchronized (firewallStateMonitor) { simpleQueues.replace(parseSeed(body)); responses.remove(SIMPLE_QUEUE_PATH); } return this; }
    String simpleQueueState() { synchronized (firewallStateMonitor) { return simpleQueues == null ? "[]" : simpleQueues.snapshot().toString(); } }

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

    String firewallFilterState() {
        synchronized (firewallStateMonitor) {
            return firewallFilters == null ? "[]" : firewallFilters.snapshot().toString();
        }
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
        String principal = principal(exchange);
        String requestBody = "GET".equals(method)
                ? ""
                : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        recordRequest(method, path, requestBody, principal);

        if (!authorized(method, exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, new FakeResponse(401, "{\"error\":401}"));
            return;
        }

        FakeResponse response = configuredResponse(path);
        if (response == null && firewallFilters != null && isFirewallFilterResource(path)) {
            response = handleStatefulFirewallFilter(path, method, requestBody);
        }
        if (response == null && simpleQueues != null && isSimpleQueueResource(path)) response = handleStatefulSimpleQueue(path, method, requestBody);
        if (response == null) {
            response = new FakeResponse(404, "{\"error\":404}");
        }
        respond(exchange, response);
    }

    private void respond(HttpExchange exchange, FakeResponse response) throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void recordRequest(String method, String path, String body, String principal) {
        CapturedRequest request = new CapturedRequest(method, path, principal);
        requests.add(request);
        if ("GET".equals(method)) {
            readJournal.add(request);
            return;
        }
        writeJournal.add(new CapturedWrite(method, path, sanitizeBody(body), principal));
    }

    private boolean authorized(String method, String authorization) {
        Credentials configured = credentials;
        if (configured == null) return true;
        String expected = "GET".equals(method) ? configured.readAuthorization() : configured.writeAuthorization();
        return expected.equals(authorization);
    }

    private static String principal(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Basic ")) return "unauthenticated";
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring("Basic ".length())), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            return separator < 0 ? "unauthenticated" : decoded.substring(0, separator);
        } catch (IllegalArgumentException exception) {
            return "unauthenticated";
        }
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

    private boolean isSimpleQueueResource(String path) { return SIMPLE_QUEUE_PATH.equals(path) || path.startsWith(SIMPLE_QUEUE_ITEM_PREFIX); }
    private FakeResponse handleStatefulSimpleQueue(String path, String method, String body) {
        synchronized (firewallStateMonitor) {
            if (SIMPLE_QUEUE_PATH.equals(path)) {
                if ("GET".equals(method)) return jsonResponse(200, simpleQueues.snapshot());
                if ("PUT".equals(method)) return simpleQueues.add(body);
                return errorResponse(405);
            }
            String id = path.substring(SIMPLE_QUEUE_ITEM_PREFIX.length());
            if ("PATCH".equals(method)) return simpleQueues.patch(id, body);
            if ("DELETE".equals(method)) return simpleQueues.delete(id);
            return errorResponse(405);
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

    record CapturedRequest(String method, String path, String principal) {
        CapturedRequest(String method, String path) { this(method, path, null); }
    }

    record CapturedWrite(String method, String path, String body, String principal) {
        CapturedWrite(String method, String path, String body) { this(method, path, body, null); }
    }

    private record Credentials(String readUsername, String readPassword, String writeUsername, String writePassword) {
        private String readAuthorization() { return basic(readUsername, readPassword); }
        private String writeAuthorization() { return basic(writeUsername, writePassword); }
        private static String basic(String username, String password) {
            return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
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

    private static final class StatefulSimpleQueues {
        private final List<ObjectNode> queues = new ArrayList<>(); private int nextId = 1;
        private void replace(List<ObjectNode> seed) { queues.clear(); for (ObjectNode value : seed) { ObjectNode copy = value.deepCopy(); if (id(copy) == null || index(id(copy)) >= 0) copy.put(".id", next()); queues.add(copy); } }
        private ArrayNode snapshot() { ArrayNode result = JSON_MAPPER.createArrayNode(); queues.forEach(queue -> result.add(queue.deepCopy())); return result; }
        private FakeResponse add(String body) { ObjectNode object = object(body); if (object == null || text(object, "name") == null || text(object, "target") == null || duplicateName(text(object, "name")) || !parentExists(text(object, "parent"))) return errorResponse(400); object.put(".id", next()); if (!object.has("disabled")) object.put("disabled", "false"); if (!object.has("dynamic")) object.put("dynamic", "false"); String before = text(object, "place-before"); object.remove("place-before"); int at = before == null ? -1 : index(before); if (before != null && at < 0) return errorResponse(400); if (at < 0) queues.add(object); else queues.add(at, object); return jsonResponse(200, object); }
        private FakeResponse patch(String resourceId, String body) { int at = index(resourceId); ObjectNode change = object(body); if (at < 0) return errorResponse(404); if (change == null || change.has("name") || change.has("comment") || change.has(".id") || !parentExists(text(change, "parent"))) return errorResponse(400); ObjectNode queue = queues.get(at); change.fields().forEachRemaining(entry -> queue.set(entry.getKey(), entry.getValue())); return jsonResponse(200, queue); }
        private FakeResponse delete(String resourceId) { int at = index(resourceId); if (at < 0) return errorResponse(404); String name = text(queues.get(at), "name"); if (queues.stream().anyMatch(queue -> name.equals(text(queue, "parent")))) return errorResponse(400); queues.remove(at); return new FakeResponse(200, "[]"); }
        private ObjectNode object(String body) { try { JsonNode parsed = JSON_MAPPER.readTree(body); return parsed instanceof ObjectNode object ? object.deepCopy() : null; } catch (IOException e) { return null; } }
        private boolean parentExists(String parent) { return parent == null || parent.isBlank() || "none".equals(parent) || queues.stream().anyMatch(queue -> parent.equals(text(queue, "name"))); }
        private boolean duplicateName(String name) { return queues.stream().anyMatch(queue -> name.equals(text(queue, "name"))); }
        private int index(String value) { for (int i = 0; i < queues.size(); i++) if (value != null && value.equals(id(queues.get(i)))) return i; return -1; }
        private String next() { String id; do { id = "*Q" + nextId++; } while (index(id) >= 0); return id; }
        private static String id(ObjectNode value) { return text(value, ".id"); }
        private static String text(ObjectNode value, String key) { JsonNode node = value.get(key); return node != null && node.isTextual() ? node.textValue() : null; }
    }

    private record FakeResponse(int status, String body) {
    }
}
