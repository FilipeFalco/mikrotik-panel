package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsInterfaceDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSystemResourceDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RouterOsRestClientTest {
    @Test
    void usesGetForEveryPhaseTwoRouterOsResource() {
        TestTransport transport = testTransport();
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/system/resource"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"version\":\"7.18.2\"}]", MediaType.APPLICATION_JSON));
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/interface"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"name\":\"ether2\",\"type\":\"ether\"}]", MediaType.APPLICATION_JSON));
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/ip/dhcp-server"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/ip/dhcp-server/lease"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/queue/simple"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/ip/firewall/filter"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<RouterOsSystemResourceDto> systemResources = transport.client().getSystemResources();
        List<RouterOsInterfaceDto> interfaces = transport.client().getInterfaces();
        transport.client().getDhcpServers(TestRouterOsDto.class);
        transport.client().getDhcpLeases(TestRouterOsDto.class);
        transport.client().getSimpleQueues(TestRouterOsDto.class);
        transport.client().getFirewallFilters(TestRouterOsDto.class);

        assertThat(systemResources).singleElement().extracting(RouterOsSystemResourceDto::version).isEqualTo("7.18.2");
        assertThat(interfaces).singleElement().extracting(RouterOsInterfaceDto::name).isEqualTo("ether2");
        transport.server().verify();
    }

    @Test
    void mapsAuthenticationFailureWithoutLeakingPasswordOrResponseBody() {
        String password = "never-log-this-router-password";
        TestTransport transport = testTransport();
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/system/resource"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"message\":\"authentication rejected: " + password + "\"}"));

        assertThatThrownBy(transport.client()::getSystemResources)
                .isInstanceOfSatisfying(RouterOsRestClientException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(RouterOsRestErrorType.AUTHENTICATION_FAILED);
                    assertThat(exception.getMessage()).doesNotContain(password);
                    assertThat(exception).hasNoCause();
                });
        transport.server().verify();
    }

    @Test
    void mapsMalformedJsonToASanitizedBadResponse() {
        String password = "not-a-loggable-password";
        TestTransport transport = testTransport();
        transport.server().expect(requestTo("http://127.0.0.1:18080/rest/interface"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{ invalid " + password, MediaType.APPLICATION_JSON));

        assertThatThrownBy(transport.client()::getInterfaces)
                .isInstanceOfSatisfying(RouterOsRestClientException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(RouterOsRestErrorType.BAD_RESPONSE);
                    assertThat(exception.getMessage()).doesNotContain(password);
                    assertThat(exception).hasNoCause();
                });
        transport.server().verify();
    }

    @Test
    void insecureTlsModeUsesAnIsolatedApacheTransportWithoutChangingGlobalSslDefaults() throws Exception {
        SSLContext defaultSslContext = SSLContext.getDefault();
        var defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        HttpComponentsClientHttpRequestFactory requestFactory = RouterOsRestClient.createRequestFactory(
                new MikrotikProperties("router.test", 443, "test-user", "test-password", false, false, false)
        );

        try {
            assertThat(requestFactory.getHttpClient()).isNotNull();
            assertThat(SSLContext.getDefault()).isSameAs(defaultSslContext);
            assertThat(HttpsURLConnection.getDefaultHostnameVerifier()).isSameAs(defaultHostnameVerifier);
        } finally {
            requestFactory.destroy();
        }
    }

    private static TestTransport testTransport() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestTransport(RouterOsRestClient.forLoopbackHttpTest(
                new MikrotikProperties(
                        "router.test", 443, "test-user", "test-password", true, false, false
                ),
                URI.create("http://127.0.0.1:18080"),
                builder
        ), server);
    }

    private record TestTransport(RouterOsRestClient client, MockRestServiceServer server) {
    }

    private record TestRouterOsDto(String name) {
    }
}
