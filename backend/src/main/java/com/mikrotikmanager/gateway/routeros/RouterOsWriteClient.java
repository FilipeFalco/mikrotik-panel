package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.ManagedDeviceBlockRule;
import com.mikrotikmanager.gateway.DeviceBlockMutationGateway;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

/**
 * The only mutable RouterOS transport in Phase 4.
 *
 * <p>There is intentionally no method accepting an arbitrary HTTP method,
 * path, RouterOS id, body, or DTO. The service constructs a domain rule and
 * this class serializes the fixed allow-listed firewall/filter shape.</p>
 */
public final class RouterOsWriteClient implements DeviceBlockMutationGateway {
    static final String FIREWALL_FILTER_PATH = "/rest/ip/firewall/filter";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final MikrotikProperties properties;
    private final RestClient restClient;

    public RouterOsWriteClient(MikrotikProperties properties) {
        this(properties, createRestClient(Objects.requireNonNull(properties, "properties")));
    }

    RouterOsWriteClient(MikrotikProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    /** Creates exactly one managed IPv4 firewall filter rule. */
    public void createManagedDeviceBlockRule(ManagedDeviceBlockRule rule, String placeBeforeId) {
        Objects.requireNonNull(rule, "rule");
        checkWritable();
        String anchor = validateOptionalAnchor(placeBeforeId);
        ObjectNode body = JSON_MAPPER.createObjectNode()
                .put("chain", rule.chain())
                .put("action", rule.action())
                .put("src-mac-address", rule.srcMacAddress())
                .put("comment", rule.comment())
                .put("disabled", Boolean.toString(rule.disabled()));
        if (anchor != null) {
            body.put("place-before", anchor);
        }
        execute(() -> restClient.put()
                .uri(FIREWALL_FILTER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Object.class));
    }

    /** Deletes one currently resolved managed firewall/filter .id. */
    public void deleteManagedDeviceBlockRule(String routerOsId) {
        checkWritable();
        String id = validateRequiredId(routerOsId);
        execute(() -> {
            restClient.delete()
                    .uri(FIREWALL_FILTER_PATH + "/" + id)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    private void checkWritable() {
        if (!properties.writeEnabled()) {
            throw new RouterOsWriteClientException(RouterOsWriteErrorType.GLOBAL_WRITES_DISABLED,
                    "RouterOS writes are disabled.");
        }
        if (!properties.deviceBlockWritesEnabled()) {
            throw new RouterOsWriteClientException(RouterOsWriteErrorType.DEVICE_BLOCK_WRITES_DISABLED,
                    "Device block writes are disabled.");
        }
        if (!properties.writeCredentialsConfigured()) {
            throw new RouterOsWriteClientException(RouterOsWriteErrorType.CREDENTIALS_MISSING,
                    "RouterOS write credentials are not configured.");
        }
    }

    private <T> T execute(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            throw responseFailure(exception.getStatusCode());
        } catch (ResourceAccessException exception) {
            // A PUT/DELETE may have reached RouterOS before the transport failed.
            throw new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN,
                    "RouterOS write outcome could not be confirmed.");
        } catch (RestClientException exception) {
            throw new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN,
                    "RouterOS write outcome could not be confirmed.");
        }
    }

    private RouterOsWriteClientException responseFailure(HttpStatusCode statusCode) {
        if (statusCode.value() == 401 || statusCode.value() == 403) {
            return new RouterOsWriteClientException(RouterOsWriteErrorType.PERMISSION_DENIED,
                    "RouterOS rejected the write permission.");
        }
        if (statusCode.value() == 404) {
            return new RouterOsWriteClientException(RouterOsWriteErrorType.NOT_FOUND,
                    "RouterOS resource was not found.");
        }
        if (statusCode.is5xxServerError()) {
            return new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN,
                    "RouterOS write outcome could not be confirmed.");
        }
        return new RouterOsWriteClientException(RouterOsWriteErrorType.REJECTED,
                "RouterOS rejected the requested write.");
    }

    private static String validateRequiredId(String id) {
        if (id == null || !id.matches("\\*[A-Za-z0-9]+")) {
            throw new RouterOsWriteClientException(RouterOsWriteErrorType.REJECTED,
                    "RouterOS resource identifier is invalid.");
        }
        return id;
    }

    private static String validateOptionalAnchor(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if ("0".equals(id)) {
            return id;
        }
        return validateRequiredId(id);
    }

    private static RestClient createRestClient(MikrotikProperties properties) {
        return RestClient.builder()
                .baseUrl(RouterOsRestClient.buildRouterOrigin(properties.host(), properties.port()))
                .requestFactory(RouterOsRestClient.createRequestFactory(properties))
                .defaultHeaders(headers -> RouterOsRestClient.applyBasicAuthentication(
                        headers, properties.writeUsername(), properties.writePassword()))
                .build();
    }
}
