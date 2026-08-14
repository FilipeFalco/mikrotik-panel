package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.ManagedSimpleQueue;
import com.mikrotikmanager.gateway.BandwidthMutationGateway;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Objects;

/** Strictly allow-listed transport for the only Fase 5 RouterOS mutations. */
public final class RouterOsQueueWriteClient implements BandwidthMutationGateway {
    static final String SIMPLE_QUEUE_PATH = "/rest/queue/simple";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MikrotikProperties properties;
    private final RestClient client;

    public RouterOsQueueWriteClient(MikrotikProperties properties) {
        this(properties, RestClient.builder()
                .baseUrl(RouterOsRestClient.buildRouterOrigin(properties.host(), properties.port()))
                .requestFactory(RouterOsRestClient.createRequestFactory(properties))
                .defaultHeaders(h -> RouterOsRestClient.applyBasicAuthentication(h, properties.writeUsername(), properties.writePassword()))
                .build());
    }
    RouterOsQueueWriteClient(MikrotikProperties properties, RestClient client) {
        this.properties = Objects.requireNonNull(properties); this.client = Objects.requireNonNull(client);
    }
    static RouterOsQueueWriteClient forLoopbackHttpTest(MikrotikProperties properties, URI base, RestClient.Builder builder) {
        if (!"http".equalsIgnoreCase(base.getScheme()) || !("127.0.0.1".equals(base.getHost()) || "localhost".equalsIgnoreCase(base.getHost()))) {
            throw new IllegalArgumentException("Test RouterOS HTTP URI must use loopback.");
        }
        return new RouterOsQueueWriteClient(properties, builder.baseUrl(base.toASCIIString())
                .defaultHeaders(h -> RouterOsRestClient.applyBasicAuthentication(h, properties.writeUsername(), properties.writePassword())).build());
    }
    @Override public void createManagedQueue(ManagedSimpleQueue queue, String placeBeforeId) {
        writable(); ObjectNode body = body(queue, true);
        if (placeBeforeId != null && !placeBeforeId.isBlank()) body.put("place-before", id(placeBeforeId));
        execute(() -> client.put().uri(SIMPLE_QUEUE_PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Object.class));
    }
    @Override public void updateManagedQueue(String freshRouterOsId, ManagedSimpleQueue queue) {
        writable(); String safe = id(freshRouterOsId); ObjectNode body = body(queue, false);
        execute(() -> client.patch().uri(SIMPLE_QUEUE_PATH + "/" + safe).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Object.class));
    }
    @Override public void deleteManagedQueue(String freshRouterOsId) {
        writable(); String safe = id(freshRouterOsId);
        execute(() -> { client.delete().uri(SIMPLE_QUEUE_PATH + "/" + safe).retrieve().toBodilessEntity(); return null; });
    }
    private ObjectNode body(ManagedSimpleQueue q, boolean create) {
        ObjectNode b = JSON.createObjectNode();
        if (create) { b.put("name", q.name()); b.put("comment", q.comment()); b.put("disabled", "false"); }
        b.put("target", q.target()); b.put("parent", q.parent());
        b.put("max-limit", RouterOsSimpleQueueRateCodec.serializeMaxLimit(q.maxLimit()));
        return b;
    }
    private void writable() {
        if (!properties.writeEnabled()) throw new RouterOsWriteClientException(RouterOsWriteErrorType.GLOBAL_WRITES_DISABLED, "RouterOS writes are disabled.");
        if (!properties.bandwidthWritesEnabled()) throw new RouterOsWriteClientException(RouterOsWriteErrorType.BANDWIDTH_WRITES_DISABLED, "Bandwidth writes are disabled.");
        if (!properties.writeCredentialsConfigured()) throw new RouterOsWriteClientException(RouterOsWriteErrorType.CREDENTIALS_MISSING, "RouterOS write credentials are not configured.");
    }
    private <T> T execute(java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (RestClientResponseException e) { throw response(e.getStatusCode()); }
        catch (RestClientException e) { throw new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN, "RouterOS write outcome could not be confirmed."); }
    }
    private RouterOsWriteClientException response(HttpStatusCode code) {
        if (code.value() == 401 || code.value() == 403) return new RouterOsWriteClientException(RouterOsWriteErrorType.PERMISSION_DENIED, "RouterOS rejected the write permission.");
        if (code.value() == 404) return new RouterOsWriteClientException(RouterOsWriteErrorType.NOT_FOUND, "RouterOS resource was not found.");
        if (code.is5xxServerError()) return new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN, "RouterOS write outcome could not be confirmed.");
        return new RouterOsWriteClientException(RouterOsWriteErrorType.REJECTED, "RouterOS rejected the requested write.");
    }
    private static String id(String value) {
        if (value == null || !value.matches("\\*[A-Za-z0-9]+")) throw new RouterOsWriteClientException(RouterOsWriteErrorType.REJECTED, "RouterOS resource identifier is invalid.");
        return value;
    }
}
