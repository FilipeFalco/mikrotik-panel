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
    private static final String FIREWALL_ADDRESS_LISTS = "/rest/ip/firewall/address-list";

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
    void keepsAllPhaseThreeReadinessReconciliationAndDryRunFlowsStrictlyGetOnlyAgainstRouterOs() throws Exception {
        saveClientPortForPhaseThree();
        ROUTER.clearRequests();

        // Existing real read-only flows remain usable before the on-demand Phase 3 flows.
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true));
        mockMvc.perform(get("/api/ports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].interfaceName").value("ether2"));
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].macAddress").value("AA:BB:CC:DD:EE:01"));
        mockMvc.perform(get("/api/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fastTrackDetected").value(true));

        mockMvc.perform(get("/api/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].resourceType").value("SIMPLE_QUEUE"))
                .andExpect(jsonPath("$.resources[0].ownership").value("MANAGED"))
                .andExpect(jsonPath("$.resources[0].status").value("IN_SYNC"))
                .andExpect(jsonPath("$.resources[0].conflict").value(false))
                .andExpect(content().string(not(containsString("\".id\""))))
                .andExpect(content().string(not(containsString("api-test-password"))));

        mockMvc.perform(get("/api/write-readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mockMode").value(false))
                .andExpect(jsonPath("$.writeFlagEnabled").value(false))
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.phaseNotice").value(containsString("FIREWALL_MAC_RULE")))
                .andExpect(jsonPath("$.checks[?(@.code == 'FASTTRACK_BANDWIDTH_WARNING')].severity").value("WARNING"))
                .andExpect(content().string(not(containsString("api-test-password"))));

        mockMvc.perform(post("/api/plans/block")
                        .contentType(APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("BLOCK_DEVICE"))
                .andExpect(jsonPath("$.target.macAddress").value("AA:BB:CC:DD:EE:01"))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.preconditions[?(@.code == 'BLOCKING_STRATEGY_DECIDED')].satisfied").value(true));
        mockMvc.perform(post("/api/plans/unblock")
                        .contentType(APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("UNBLOCK_DEVICE"))
                .andExpect(jsonPath("$.changeRequired").value(false))
                .andExpect(jsonPath("$.executable").value(false));
        mockMvc.perform(post("/api/plans/port-speed")
                        .contentType(APPLICATION_JSON)
                        .content("{\"interfaceName\":\"ether2\",\"downloadBps\":20000000,\"uploadBps\":5000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("SET_PORT_SPEED"))
                .andExpect(jsonPath("$.changeRequired").value(false))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.warnings[?(@.code == 'FASTTRACK_ACTIVE')].severity").value("WARNING"));
        mockMvc.perform(post("/api/plans/device-speed")
                        .contentType(APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\",\"downloadBps\":10000000,\"uploadBps\":2000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("SET_DEVICE_SPEED"))
                .andExpect(jsonPath("$.target.interfaceName").value("ether2"))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.warnings[?(@.code == 'FASTTRACK_ACTIVE')].severity").value("WARNING"))
                .andExpect(content().string(not(containsString("api-test-password"))));

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
        assertThat(ROUTER.requests()).extracting(FakeRouterOsServer.CapturedRequest::path)
                .contains(SYSTEM_RESOURCE, INTERFACES, DHCP_SERVERS, DHCP_LEASES, SIMPLE_QUEUES,
                        FIREWALL_FILTERS, FIREWALL_ADDRESS_LISTS);
    }

    @Test
    void combinedWriteAnalysisUsesOneSnapshotAndDoesNotDuplicateRouterOsCollections() throws Exception {
        saveClientPortForPhaseThree();
        ROUTER.clearRequests();

        mockMvc.perform(get("/api/write-analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotFingerprint").isString())
                .andExpect(jsonPath("$.readiness.executionEnabled").value(false))
                .andExpect(jsonPath("$.readiness.summary.managedPorts").value(1))
                .andExpect(jsonPath("$.readiness.summary.validManagedPorts").value(1))
                .andExpect(jsonPath("$.reconciliation.resources[0].resourceType").value("SIMPLE_QUEUE"))
                .andExpect(jsonPath("$.reconciliation.snapshotFingerprint").isString())
                .andExpect(content().string(not(containsString("api-test-password"))));

        // Readiness and reconciliation share one snapshot: each RouterOS
        // collection is read at most once, plus one /system/resource for the
        // connection status probe. No collection is duplicated.
        assertThat(ROUTER.requestCount(INTERFACES)).isEqualTo(1);
        assertThat(ROUTER.requestCount(DHCP_SERVERS)).isEqualTo(1);
        assertThat(ROUTER.requestCount(DHCP_LEASES)).isEqualTo(1);
        assertThat(ROUTER.requestCount(SIMPLE_QUEUES)).isEqualTo(1);
        assertThat(ROUTER.requestCount(FIREWALL_FILTERS)).isEqualTo(1);
        assertThat(ROUTER.requestCount(FIREWALL_ADDRESS_LISTS)).isEqualTo(1);
        assertThat(ROUTER.requestCount(SYSTEM_RESOURCE)).isLessThanOrEqualTo(1);
        assertThat(ROUTER.requests()).allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void returnsStructuredInvalidDeviceDryRunAndPreservesTheParentLimitInvariant() throws Exception {
        saveClientPortForPhaseThree();
        ROUTER.clearRequests();

        mockMvc.perform(post("/api/plans/block")
                        .contentType(APPLICATION_JSON)
                        .content("{\"macAddress\":\"not-a-mac\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("BLOCK_DEVICE"))
                .andExpect(jsonPath("$.target.identifier").value("invalid-mac"))
                .andExpect(jsonPath("$.preconditions[?(@.code == 'DEVICE_HAS_MAC')].satisfied").value(false))
                .andExpect(jsonPath("$.preconditions[?(@.code == 'DEVICE_EXISTS')].satisfied").value(false))
                .andExpect(jsonPath("$.executable").value(false));

        mockMvc.perform(post("/api/plans/device-speed")
                        .contentType(APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\",\"downloadBps\":150000000,\"uploadBps\":10000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("SET_DEVICE_SPEED"))
                .andExpect(jsonPath("$.preconditions[?(@.code == 'LIMIT_WITHIN_PARENT')].satisfied").value(false))
                .andExpect(jsonPath("$.preconditions[?(@.code == 'LIMIT_WITHIN_PARENT')].severity").value("BLOCKING"))
                .andExpect(jsonPath("$.readyForFutureExecution").value(false))
                .andExpect(jsonPath("$.executable").value(false));

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void exposesOnDemandFakeRouterOsReconciliationFixturesWithoutAdoptingManualResources() throws Exception {
        saveClientPortForPhaseThree();

        stubSimpleQueues("""
                [{".id":"*20","name":"mtmgr-port-ether2","comment":"MTMGR:PORT:ether2",
                  "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.10.10.0/24"}]
                """);
        assertReconciliation("MANAGED", "IN_SYNC", false);

        stubSimpleQueues("""
                [{".id":"*20","name":"mtmgr-port-ether2","comment":"MTMGR:PORT:ether2",
                  "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.99.0.0/24"}]
                """);
        assertReconciliation("MANAGED", "DRIFTED", false);

        stubSimpleQueues("""
                [{".id":"*20","name":"manual-client-limit","comment":"Criada manualmente",
                  "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.10.10.0/24"}]
                """);
        assertReconciliation("FOREIGN", "CONFLICT", true);

        stubSimpleQueues("""
                [
                  {".id":"*20","name":"mtmgr-port-ether2-a","comment":"MTMGR:PORT:ether2",
                   "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.10.10.0/24"},
                  {".id":"*21","name":"mtmgr-port-ether2-b","comment":"MTMGR:PORT:ether2",
                   "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.10.10.0/24"}
                ]
                """);
        assertReconciliation("MANAGED", "AMBIGUOUS_OWNERSHIP", true);

        stubSimpleQueues("[]");
        assertReconciliation("UNKNOWN", "MISSING", false);

        stubSimpleQueues("""
                [{".id":"*20","name":"mtmgr-port-ether2","comment":"manual",
                  "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.50.0.0/24"}]
                """);
        assertReconciliation("FOREIGN", "CONFLICT", true);

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
        assertThat(ROUTER.requestCount(FIREWALL_ADDRESS_LISTS)).isEqualTo(6);
    }

    @Test
    void treatsManualFirewallFilterWithExactDeviceIpAsForeignForUnblockDryRun() throws Exception {
        saveClientPortForPhaseThree();
        ROUTER.respondJson(FIREWALL_FILTERS, """
                [{
                  ".id":"*45","action":"drop","disabled":"false","dynamic":"false","chain":"forward",
                  "src-address":"10.10.10.21","comment":"Bloqueio manual"
                }]
                """);
        ROUTER.clearRequests();

        assertForeignFirewallUnblockPlan();

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
        assertThat(ROUTER.requests()).extracting(FakeRouterOsServer.CapturedRequest::path)
                .contains(FIREWALL_FILTERS, FIREWALL_ADDRESS_LISTS);
    }

    @Test
    void treatsManualFirewallAddressListMatchAsForeignForUnblockDryRun() throws Exception {
        saveClientPortForPhaseThree();
        ROUTER.respondJson(FIREWALL_FILTERS, """
                [{
                  ".id":"*46","action":"reject","disabled":"false","dynamic":"false","chain":"forward",
                  "src-address-list":"manual-blocked-devices","comment":"Bloqueio manual via lista"
                }]
                """);
        ROUTER.respondJson(FIREWALL_ADDRESS_LISTS, """
                [{
                  ".id":"*47","list":"manual-blocked-devices","address":"10.10.10.21",
                  "comment":"Entrada manual","disabled":"false","dynamic":"false"
                }]
                """);
        ROUTER.clearRequests();

        assertForeignFirewallUnblockPlan();

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
        assertThat(ROUTER.requests()).extracting(FakeRouterOsServer.CapturedRequest::path)
                .contains(FIREWALL_FILTERS, FIREWALL_ADDRESS_LISTS);
    }

    @Test
    void doesNotOverreachByTreatingBroadManualFirewallCidrAsDeviceSpecificBlock() throws Exception {
        saveClientPortForPhaseThree();
        ROUTER.respondJson(FIREWALL_FILTERS, """
                [{
                  ".id":"*48","action":"drop","disabled":"false","dynamic":"false","chain":"forward",
                  "src-address":"10.10.10.0/24","comment":"Regra manual de rede"
                }]
                """);
        ROUTER.clearRequests();

        mockMvc.perform(post("/api/plans/unblock")
                        .contentType(APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("UNBLOCK_DEVICE"))
                .andExpect(jsonPath("$.ownership").value("UNKNOWN"))
                .andExpect(jsonPath("$.currentState.blocked").value(false))
                .andExpect(jsonPath("$.changeRequired").value(false))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.executable").value(false));

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void sanitizesUnreadableRouterResponseInWriteReadinessInsteadOfLeakingRouterBody() throws Exception {
        String sensitiveRouterBody = "password=do-not-leak-raw-router-body";
        ROUTER.respond(FIREWALL_ADDRESS_LISTS, 500, "{\"message\":\"" + sensitiveRouterBody + "\"}");

        mockMvc.perform(get("/api/write-readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.readyForFutureExecution").value(false))
                .andExpect(content().string(not(containsString(sensitiveRouterBody))))
                .andExpect(content().string(not(containsString("api-test-password"))));

        assertThat(ROUTER.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.method()).isEqualTo("GET"));
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
        ROUTER.respondJson(FIREWALL_ADDRESS_LISTS, "[]");
    }

    private void saveClientPortForPhaseThree() throws Exception {
        mockMvc.perform(put("/api/ports/ether2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "friendlyName":"Clientes",
                                  "description":"Metadata local para testes de Fase 3",
                                  "network":"10.10.10.0/24",
                                  "dhcpServer":"dhcp-cliente1",
                                  "enabled":true,
                                  "role":"CLIENT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managed").value(true));
    }

    private void stubSimpleQueues(String queues) {
        ROUTER.respondJson(SIMPLE_QUEUES, queues);
    }

    private void assertReconciliation(String ownership, String reconciliationStatus, boolean conflict) throws Exception {
        mockMvc.perform(get("/api/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].ownership").value(ownership))
                .andExpect(jsonPath("$.resources[0].status").value(reconciliationStatus))
                .andExpect(jsonPath("$.resources[0].conflict").value(conflict))
                .andExpect(content().string(not(containsString("\".id\""))));
    }

    private void assertForeignFirewallUnblockPlan() throws Exception {
        mockMvc.perform(post("/api/plans/unblock")
                        .contentType(APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("UNBLOCK_DEVICE"))
                .andExpect(jsonPath("$.ownership").value("FOREIGN"))
                .andExpect(jsonPath("$.currentState.blocked").value(true))
                .andExpect(jsonPath("$.changeRequired").value(true))
                .andExpect(jsonPath("$.readyForFutureExecution").value(false))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.conflicts[?(@.code == 'UNOWNED_BLOCK_RESOURCE')].resourceType")
                        .value("FIREWALL_FILTER"))
                .andExpect(jsonPath("$.conflicts[?(@.code == 'UNOWNED_BLOCK_RESOURCE')].ownership").value("FOREIGN"))
                .andExpect(content().string(not(containsString("\".id\""))))
                .andExpect(content().string(not(containsString("api-test-password"))));
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
