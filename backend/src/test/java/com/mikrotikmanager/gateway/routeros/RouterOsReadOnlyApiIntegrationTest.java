package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.MikrotikManagerApplication;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level regression tests for real RouterOS mode with writes disabled.
 *
 * <p>The primary test bean is the real read-only RouterOS gateway wired to a
 * loopback fake. This exercises the normal service/controller stack while the
 * fake independently proves that local SQLite writes do not mutate RouterOS.
 */
@SpringBootTest(
        classes = MikrotikManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "mikrotik.mock-mode=false",
                "mikrotik.write-enabled=false",
                "mikrotik.verify-ssl=true"
        }
)
@AutoConfigureMockMvc
@Import(RouterOsReadOnlyApiIntegrationTest.LoopbackRouterGatewayConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouterOsReadOnlyApiIntegrationTest {
    private static final String SYSTEM_RESOURCE = "/rest/system/resource";
    private static final String INTERFACES = "/rest/interface";
    private static final String DHCP_SERVERS = "/rest/ip/dhcp-server";
    private static final String DHCP_LEASES = "/rest/ip/dhcp-server/lease";
    private static final String SIMPLE_QUEUES = "/rest/queue/simple";
    private static final String FIREWALL_FILTERS = "/rest/ip/firewall/filter";

    private static final FakeRouterOsServer ROUTER = FakeRouterOsServer.start();
    private static final Path DATABASE_DIRECTORY = createTemporaryDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("routeros-readonly-api.db");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HikariDataSource dataSource;

    @DynamicPropertySource
    static void configureRealReadOnlyRouter(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + toJdbcPath(DATABASE_FILE));
        registry.add("mikrotik.host", () -> "127.0.0.1");
        registry.add("mikrotik.port", () -> ROUTER.baseUri().getPort());
        registry.add("mikrotik.username", () -> "mtmgr-api-test");
        registry.add("mikrotik.password", () -> "api-test-password");
        registry.add("mikrotik.connect-timeout-ms", () -> 500);
        registry.add("mikrotik.read-timeout-ms", () -> 1_000);
    }

    @BeforeEach
    void resetLocalAndRouterFixtures() {
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM managed_device");
        jdbcTemplate.update("DELETE FROM managed_port");
        stubHealthyRouter();
        ROUTER.clearRequests();
    }

    @Test
    void exposesRealReadOnlyRouterDataThroughStatusPortsDevicesAndDiagnostics() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.mockMode").value(false))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.routerOsVersion").value("7.18.2 (stable)"))
                .andExpect(jsonPath("$.latencyMillis").isNumber());

        mockMvc.perform(get("/api/ports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].interfaceName").value("ether2"))
                .andExpect(jsonPath("$[0].devices[0].hostname").value("Galaxy S25"))
                .andExpect(jsonPath("$[0].downloadLimitBps").value(20_000_000));

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hostname").value("Galaxy S25"))
                .andExpect(jsonPath("$[0].interfaceName").value("ether2"));

        mockMvc.perform(get("/api/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.fastTrackDetected").value(true))
                .andExpect(jsonPath("$.checks.length()").value(5));

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void exposesDiscoveredInterfacesAsUnmanagedUntilLocalMetadataIsSaved() throws Exception {
        mockMvc.perform(get("/api/ports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].interfaceName").value("ether2"))
                .andExpect(jsonPath("$[0].managed").value(false))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].role").value(nullValue()));

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void persistsLocalDeviceAndPortUpdatesWhileRouterRequestsRemainGetOnly() throws Exception {
        mockMvc.perform(put("/api/devices/AA:BB:CC:DD:EE:01")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"friendlyName":"Galaxy local","notes":"metadata local"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendlyName").value("Galaxy local"))
                .andExpect(jsonPath("$.notes").value("metadata local"));

        ROUTER.clearRequests();
        mockMvc.perform(put("/api/ports/ether2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "friendlyName":"Clientes locais",
                                  "description":"Configuração SQLite",
                                  "network":"10.10.10.0/24",
                                  "dhcpServer":"dhcp-cliente1",
                                  "enabled":true,
                                  "role":"CLIENT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managed").value(true))
                .andExpect(jsonPath("$.friendlyName").value("Clientes locais"))
                .andExpect(jsonPath("$.network").value("10.10.10.0/24"))
                .andExpect(jsonPath("$.role").value("CLIENT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM managed_port WHERE interface_name = 'ether2'", String.class))
                .isEqualTo("CLIENT");

        // PUT persists local SQLite metadata and validates the discovered
        // interface. It must not fan out into DHCP or queue RouterOS reads.
        assertThat(ROUTER.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo(INTERFACES);
        });

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].friendlyName").value("Galaxy local"));

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void keepsOneLocalWanAndPreservesTheExistingRoleWhenRoleIsOmitted() throws Exception {
        mockMvc.perform(put("/api/ports/ether5")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "friendlyName":"Link de backup",
                                  "description":"Somente metadata local",
                                  "network":"10.20.0.0/24",
                                  "dhcpServer":"dhcp-backup",
                                  "enabled":true,
                                  "role":"WAN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WAN"));

        // Older clients do not send role. A full local metadata update must
        // keep its existing WAN role instead of silently changing it.
        mockMvc.perform(put("/api/ports/ether5")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "friendlyName":"Link principal local",
                                  "description":"Metadata atualizada",
                                  "network":"10.20.0.0/24",
                                  "dhcpServer":"dhcp-backup",
                                  "enabled":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WAN"));

        mockMvc.perform(put("/api/ports/ether2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "friendlyName":"Novo link principal",
                                  "description":"Metadata local",
                                  "network":"10.10.10.0/24",
                                  "dhcpServer":"dhcp-cliente1",
                                  "enabled":true,
                                  "role":"WAN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WAN"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM managed_port WHERE interface_name = 'ether2'", String.class))
                .isEqualTo("WAN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM managed_port WHERE interface_name = 'ether5'", String.class))
                .isEqualTo("CLIENT");
        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void rejectsAllRouterWriteEndpointsBeforeTheyCanIssueAnyRouterRequest() throws Exception {
        String speed = "{\"downloadBps\":20000000,\"uploadBps\":5000000}";

        mockMvc.perform(put("/api/devices/AA:BB:CC:DD:EE:01/speed")
                        .contentType(APPLICATION_JSON).content(speed))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MIKROTIK_WRITES_DISABLED"));
        mockMvc.perform(post("/api/devices/AA:BB:CC:DD:EE:01/block"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MIKROTIK_WRITES_DISABLED"));
        mockMvc.perform(delete("/api/devices/AA:BB:CC:DD:EE:01/block"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MIKROTIK_WRITES_DISABLED"));
        mockMvc.perform(put("/api/ports/ether2/speed")
                        .contentType(APPLICATION_JSON).content(speed))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MIKROTIK_WRITES_DISABLED"));

        assertThat(ROUTER.requests()).isEmpty();
    }

    @Test
    void mapsRouterAuthenticationFailureToASanitizedApiError() throws Exception {
        String password = "api-authentication-password-must-not-leak";
        ROUTER.respond(INTERFACES, 401, "{\"message\":\"denied " + password + "\"}");

        mockMvc.perform(get("/api/ports"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MIKROTIK_AUTHENTICATION_FAILED"))
                .andExpect(content().string(not(containsString(password))));

        assertThat(ROUTER.requests()).singleElement()
                .satisfies(request -> assertThat(request.method()).isEqualTo("GET"));
    }

    @AfterAll
    void closeFakeAndRemoveTemporaryDatabase() throws IOException {
        dataSource.close();
        ROUTER.close();
        deleteRecursively(DATABASE_DIRECTORY);
    }

    private static void stubHealthyRouter() {
        ROUTER.respondJson(SYSTEM_RESOURCE, """
                [{".id":"*0","version":"7.18.2 (stable)","uptime":"2d3h","cpu-load":"17"}]
                """);
        ROUTER.respondJson(INTERFACES, """
                [
                  {".id":"*2","name":"ether2","type":"ether","running":"true","disabled":"false"},
                  {".id":"*5","name":"ether5","type":"ether","running":"true","disabled":"false"}
                ]
                """);
        ROUTER.respondJson(DHCP_SERVERS, """
                [{".id":"*11","name":"dhcp-cliente1","interface":"ether2","disabled":"false"}]
                """);
        ROUTER.respondJson(DHCP_LEASES, """
                [{
                  ".id":"*A1","address":"10.10.10.21","mac-address":"AA:BB:CC:DD:EE:01",
                  "host-name":"Galaxy S25","server":"dhcp-cliente1","status":"bound",
                  "block-access":"false","dynamic":"true","disabled":"false"
                }]
                """);
        ROUTER.respondJson(SIMPLE_QUEUES, """
                [{
                  ".id":"*20","name":"mtmgr-port-ether2","comment":"MTMGR:PORT:ether2",
                  "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.10.10.0/24"
                }]
                """);
        ROUTER.respondJson(FIREWALL_FILTERS, """
                [{
                  ".id":"*30","action":"fasttrack-connection","disabled":"false","chain":"forward",
                  "comment":"defconf: fasttrack"
                }]
                """);
    }

    private static Path createTemporaryDatabaseDirectory() {
        try {
            return Files.createTempDirectory("mikrotik-routeros-readonly-api-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create a temporary SQLite directory.", exception);
        }
    }

    private static String toJdbcPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackRouterGatewayConfiguration {
        @Bean
        @Primary
        MikrotikGateway loopbackReadOnlyRouterGateway(MikrotikProperties properties) {
            RouterOsRestClient client = RouterOsRestClient.forLoopbackHttpTest(
                    properties, ROUTER.baseUri(), RestClient.builder());
            return new RouterOsRestGateway(properties, client, new RouterOsDhcpMapper());
        }
    }
}
