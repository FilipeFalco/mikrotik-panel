package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsInterfaceDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSystemResourceDto;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * RouterOS REST transport restricted by construction to collection {@code GET}
 * requests. It deliberately has no method accepting an HTTP method, request
 * body, or arbitrary REST path.
 *
 * <p>RouterOS values are represented by DTOs in this package only. Callers
 * outside the RouterOS gateway receive application domain models from
 * {@code RouterOsRestGateway}, never REST JSON.
 */
public final class RouterOsRestClient {
    /*
     * Keep `/rest` in each absolute URI path. Spring's URI handling treats a
     * leading slash as root-relative, so a base URL ending in `/rest` could be
     * replaced accidentally instead of extended.
     */
    private static final String SYSTEM_RESOURCE_PATH = "/rest/system/resource";
    private static final String INTERFACE_PATH = "/rest/interface";
    private static final String DHCP_SERVER_PATH = "/rest/ip/dhcp-server";
    private static final String DHCP_LEASE_PATH = "/rest/ip/dhcp-server/lease";
    private static final String SIMPLE_QUEUE_PATH = "/rest/queue/simple";
    private static final String FIREWALL_FILTER_PATH = "/rest/ip/firewall/filter";

    private final RestClient restClient;

    public RouterOsRestClient(MikrotikProperties properties) {
        this.restClient = createRestClient(Objects.requireNonNull(properties, "properties"));
    }

    /** Visible to package-local transport tests without exposing a test hook outside this package. */
    RouterOsRestClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    /**
     * Test-only construction hook for the loopback fake RouterOS server.
     * Production code has no HTTP fallback: it can use only the public
     * constructor, which always builds an HTTPS origin from properties.
     */
    static RouterOsRestClient forLoopbackHttpTest(
            MikrotikProperties properties,
            URI baseUri,
            RestClient.Builder builder
    ) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(builder, "builder");
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) || !isLoopbackHost(baseUri.getHost())) {
            throw new IllegalArgumentException("Test RouterOS HTTP URI must use a loopback http host");
        }
        return new RouterOsRestClient(builder
                .baseUrl(baseUri.toASCIIString())
                .defaultHeaders(headers -> applyBasicAuthentication(headers, properties))
                .build());
    }

    public List<RouterOsSystemResourceDto> getSystemResources() {
        return getCollection(SYSTEM_RESOURCE_PATH, RouterOsSystemResourceDto.class);
    }

    public List<RouterOsInterfaceDto> getInterfaces() {
        return getCollection(INTERFACE_PATH, RouterOsInterfaceDto.class);
    }

    /**
     * Reads the {@code /ip/dhcp-server} collection. The owning DHCP workstream
     * supplies the RouterOS-only DTO type at the call site.
     */
    public <T> List<T> getDhcpServers(Class<T> elementType) {
        return getCollection(DHCP_SERVER_PATH, elementType);
    }

    /** Reads the {@code /ip/dhcp-server/lease} collection. */
    public <T> List<T> getDhcpLeases(Class<T> elementType) {
        return getCollection(DHCP_LEASE_PATH, elementType);
    }

    /** Reads the {@code /queue/simple} collection. */
    public <T> List<T> getSimpleQueues(Class<T> elementType) {
        return getCollection(SIMPLE_QUEUE_PATH, elementType);
    }

    /** Reads the {@code /ip/firewall/filter} collection. */
    public <T> List<T> getFirewallFilters(Class<T> elementType) {
        return getCollection(FIREWALL_FILTER_PATH, elementType);
    }

    private <T> List<T> getCollection(String path, Class<T> elementType) {
        ParameterizedTypeReference<List<T>> responseType = collectionType(elementType);
        List<T> response = execute(() -> restClient.get()
                .uri(path)
                .retrieve()
                .body(responseType));
        return response == null ? List.of() : List.copyOf(response);
    }

    private static RestClient createRestClient(MikrotikProperties properties) {
        return RestClient.builder()
                .baseUrl(buildRouterOrigin(properties.host(), properties.port()))
                .requestFactory(createRequestFactory(properties))
                .defaultHeaders(headers -> applyBasicAuthentication(headers, properties))
                .build();
    }

    /** Visible to package-local tests so TLS isolation can be verified without a network call. */
    static HttpComponentsClientHttpRequestFactory createRequestFactory(MikrotikProperties properties) {
        // Timeouts are configured on the client-local Apache RequestConfig.
        return new HttpComponentsClientHttpRequestFactory(createHttpClient(properties));
    }

    private static CloseableHttpClient createHttpClient(MikrotikProperties properties) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeoutMs()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.connectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(properties.readTimeoutMs()))
                .build();
        var clientBuilder = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig);

        if (properties.verifySsl()) {
            /*
             * Explicitly use Apache's standard socket factory, which keeps
             * normal certificate-chain and hostname/IP verification local to
             * this client. No permissive TLS component is installed here.
             */
            clientBuilder.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(SSLConnectionSocketFactory.getSocketFactory())
                    .build());
        } else {
            /*
             * This intentionally weak TLS policy is isolated in this one
             * RouterOS Apache client and its connection manager. It is enabled
             * only when an operator explicitly chooses verifySsl=false for a
             * controlled local environment using a self-signed RouterOS
             * certificate, including a certificate whose hostname/IP does not
             * match MIKROTIK_HOST. It never changes JVM-wide SSL defaults,
             * system properties, or any other application HTTP client.
             */
            clientBuilder.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                            .setSslContext(createInsecureRouterOsSslContext())
                            .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                            .build())
                    .build());
        }

        return clientBuilder.build();
    }

    private static void applyBasicAuthentication(HttpHeaders headers, MikrotikProperties properties) {
        String username = properties.username() == null ? "" : properties.username();
        String password = properties.password() == null ? "" : properties.password();
        headers.setBasicAuth(username, password);
    }

    private static SSLContext createInsecureRouterOsSslContext() {
        try {
            return SSLContexts.custom()
                    .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                    .build();
        } catch (GeneralSecurityException exception) {
            throw new RouterOsRestClientException(
                    RouterOsRestErrorType.TLS_ERROR,
                    "RouterOS TLS client could not be initialized."
            );
        }
    }

    private static String buildRouterOrigin(String host, int port) {
        String normalizedHost = normalizeHost(host);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("mikrotik.port must be between 1 and 65535");
        }
        try {
            return new URI("https", null, normalizedHost, port, null, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("mikrotik.host must be a valid host name or IP address");
        }
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("mikrotik.host must not be blank");
        }
        String normalized = host.trim();
        if (normalized.contains("://") || normalized.contains("/") || normalized.contains("@")
                || normalized.contains("?") || normalized.contains("#")) {
            throw new IllegalArgumentException("mikrotik.host must not contain a URL, path, or credentials");
        }
        return normalized;
    }

    private static boolean isLoopbackHost(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private <T> T execute(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            throw responseFailure(exception.getStatusCode());
        } catch (ResourceAccessException exception) {
            throw resourceFailure(exception);
        } catch (RestClientException exception) {
            throw new RouterOsRestClientException(
                    RouterOsRestErrorType.BAD_RESPONSE,
                    "RouterOS returned an unexpected response."
            );
        }
    }

    private static RouterOsRestClientException responseFailure(HttpStatusCode statusCode) {
        if (statusCode.value() == 401 || statusCode.value() == 403) {
            return new RouterOsRestClientException(
                    RouterOsRestErrorType.AUTHENTICATION_FAILED,
                    "RouterOS authentication failed."
            );
        }
        if (statusCode.is5xxServerError()) {
            return new RouterOsRestClientException(
                    RouterOsRestErrorType.UNAVAILABLE,
                    "RouterOS is unavailable."
            );
        }
        return new RouterOsRestClientException(
                RouterOsRestErrorType.BAD_RESPONSE,
                "RouterOS returned an unexpected response."
        );
    }

    private static RouterOsRestClientException resourceFailure(ResourceAccessException exception) {
        if (hasTlsCause(exception)) {
            return new RouterOsRestClientException(
                    RouterOsRestErrorType.TLS_ERROR,
                    "RouterOS TLS verification failed."
            );
        }
        return new RouterOsRestClientException(
                RouterOsRestErrorType.UNAVAILABLE,
                "RouterOS is unavailable."
        );
    }

    private static boolean hasTlsCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SSLException || current instanceof CertificateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T> ParameterizedTypeReference<List<T>> collectionType(Class<T> elementType) {
        Objects.requireNonNull(elementType, "elementType");
        return ParameterizedTypeReference.forType(
                ResolvableType.forClassWithGenerics(List.class, elementType).getType()
        );
    }

}
