package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikrotikmanager.MikrotikManagerApplication;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.gateway.BandwidthMutationGateway;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller -> real executor/planner -> real queue writer -> stateful loopback RouterOS. */
@SpringBootTest(classes = {MikrotikManagerApplication.class, BandwidthHttpIntegrationTest.LoopbackRouterOsConfig.class})
@AutoConfigureMockMvc
class BandwidthHttpIntegrationTest {
    private static final String MAC_A = "AA:BB:CC:DD:EE:01";
    private static final String MAC_B = "AA:BB:CC:DD:EE:02";
    private static final FakeRouterOsServer FAKE = FakeRouterOsServer.start().enableStatefulSimpleQueues()
            .requireCredentials("read-user", "read-password", "write-user", "write-password");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private ManagedPortRepository ports;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mikrotik.mock-mode", () -> "false");
        registry.add("mikrotik.host", () -> "127.0.0.1");
        registry.add("mikrotik.port", () -> FAKE.baseUri().getPort());
        registry.add("mikrotik.username", () -> "read-user");
        registry.add("mikrotik.password", () -> "read-password");
        registry.add("mikrotik.write-username", () -> "write-user");
        registry.add("mikrotik.write-password", () -> "write-password");
        registry.add("mikrotik.write-enabled", () -> "true");
        registry.add("mikrotik.bandwidth-writes-enabled", () -> "true");
        registry.add("mikrotik.device-block-writes-enabled", () -> "false");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:/tmp/mikrotik-phase5-bandwidth-http-integration.db");
    }

    @BeforeEach
    void resetState() {
        ports.save(new ManagedPort(0L, "ether2", "Clientes", "", "10.10.10.0/24", "dhcp-clientes",
                ManagedPortRole.CLIENT, true, null, null));
        FAKE.respondJson("/rest/interface", "[{\".id\":\"*2\",\"name\":\"ether2\",\"type\":\"ether\",\"running\":\"true\",\"disabled\":\"false\"}]");
        setLeases("10.10.10.21", "10.10.10.22");
        FAKE.respondJson("/rest/ip/dhcp-server", "[{\".id\":\"*d\",\"name\":\"dhcp-clientes\",\"interface\":\"ether2\",\"disabled\":\"false\"}]");
        FAKE.respondJson("/rest/ip/firewall/filter", "[]");
        FAKE.respondJson("/rest/ip/firewall/address-list", "[]");
        FAKE.respondJson("/rest/system/resource", "[{\".id\":\"*0\",\"version\":\"7.21\"}]");
        FAKE.seedSimpleQueues("[]");
        FAKE.clearRequests();
    }

    @AfterAll
    static void closeFake() { FAKE.close(); }

    @Test
    void portCreateUpdateNoOpAndRemoveUseOnlyTheStatefulSimpleQueueContract() throws Exception {
        setPortSpeed(100_000_000, 20_000_000).andExpect(status().isOk());
        assertThat(FAKE.writeJournal()).singleElement().satisfies(write -> {
            assertThat(write.method()).isEqualTo("PUT");
            assertThat(write.path()).isEqualTo("/rest/queue/simple");
            assertThat(write.principal()).isEqualTo("write-user");
            JsonNode body = json(write.body());
            assertThat(body.path("max-limit").asText()).isEqualTo("20M/100M");
            assertThat(body.path("comment").asText()).isEqualTo("MTMGR:PORT:ether2");
            assertThat(body.path("target").asText()).isEqualTo("10.10.10.0/24");
            assertThat(body.path("parent").asText()).isEqualTo("none");
        });
        mockMvc.perform(get("/api/ports")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].downloadLimitBps").value(100_000_000))
                .andExpect(jsonPath("$[0].uploadLimitBps").value(20_000_000));

        int writesAfterCreate = FAKE.writeJournal().size();
        setPortSpeed(80_000_000, 10_000_000).andExpect(status().isOk());
        assertThat(FAKE.writeJournal()).hasSize(writesAfterCreate + 1);
        assertThat(FAKE.writeJournal().getLast().method()).isEqualTo("PATCH");

        int writesAfterUpdate = FAKE.writeJournal().size();
        setPortSpeed(80_000_000, 10_000_000).andExpect(status().isOk());
        assertThat(FAKE.writeJournal()).hasSize(writesAfterUpdate);

        setPortSpeed(0, 0).andExpect(status().isOk());
        assertThat(FAKE.writeJournal().getLast().method()).isEqualTo("DELETE");
        assertOnlyAllowlistedQueueWrites();
    }

    @Test
    void deviceCreateUpdateNoOpRemoveAndDhcpTargetDriftAreStateful() throws Exception {
        setDeviceSpeed(MAC_A, 40_000_000, 10_000_000).andExpect(status().isOk());
        assertThat(FAKE.writeJournal()).singleElement().satisfies(write -> {
            assertThat(write.method()).isEqualTo("PUT");
            JsonNode body = json(write.body());
            assertThat(body.path("comment").asText()).isEqualTo("MTMGR:DEVICE:AA-BB-CC-DD-EE-01");
            assertThat(body.path("target").asText()).isEqualTo("10.10.10.21/32");
        });
        setDeviceSpeed(MAC_A, 50_000_000, 15_000_000).andExpect(status().isOk());
        int writesAfterUpdate = FAKE.writeJournal().size();
        setDeviceSpeed(MAC_A, 50_000_000, 15_000_000).andExpect(status().isOk());
        assertThat(FAKE.writeJournal()).hasSize(writesAfterUpdate);

        setLeases("10.10.10.45", "10.10.10.22");
        setDeviceSpeed(MAC_A, 50_000_000, 15_000_000).andExpect(status().isOk());
        assertThat(FAKE.writeJournal().getLast()).satisfies(write -> {
            assertThat(write.method()).isEqualTo("PATCH");
            assertThat(json(write.body()).path("target").asText()).isEqualTo("10.10.10.45/32");
        });

        setDeviceSpeed(MAC_A, 0, 0).andExpect(status().isOk());
        assertThat(FAKE.writeJournal().getLast().method()).isEqualTo("DELETE");
        assertOnlyAllowlistedQueueWrites();
    }

    @Test
    void targetDriftWithForeignQueueIsConflictAndWritesNothing() throws Exception {
        FAKE.seedSimpleQueues("""
                [{".id":"*Q1","name":"mtmgr-device-AABBCCDDEE01","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-01","target":"10.10.10.21/32","parent":"none","max-limit":"15M/50M","disabled":"false","dynamic":"false"},
                 {".id":"*M1","name":"manual","comment":"manual","target":"10.10.10.45/32","parent":"none","max-limit":"1M/1M","disabled":"false","dynamic":"false"}]
                """);
        setLeases("10.10.10.45", "10.10.10.22");
        FAKE.clearRequests();

        setDeviceSpeed(MAC_A, 50_000_000, 15_000_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUEUE_FOREIGN_CONFLICT"));

        assertThat(FAKE.writeJournal()).isEmpty();
    }

    @Test
    void hierarchyPreflightCreatesThenReparentsOnlySafeChildrenAndRejectsBurstBeforeParentPut() throws Exception {
        FAKE.seedSimpleQueues(safeStandaloneChildren());
        setPortSpeed(100_000_000, 20_000_000).andExpect(status().isOk());
        assertThat(FAKE.writeJournal()).extracting(FakeRouterOsServer.CapturedWrite::method).containsExactly("PUT", "PATCH", "PATCH");

        FAKE.seedSimpleQueues(safeStandaloneChildren().replace("\"max-limit\":\"5M/40M\"", "\"max-limit\":\"5M/40M\",\"burst-limit\":\"10M/10M\""));
        FAKE.clearRequests();
        setPortSpeed(100_000_000, 20_000_000).andExpect(status().isConflict());
        assertThat(FAKE.writeJournal()).isEmpty();
    }

    @Test
    void hierarchyRemovalDetachesEveryValidatedChildBeforeDeletingTheParent() throws Exception {
        FAKE.seedSimpleQueues(parentWithChildren());

        setPortSpeed(0, 0).andExpect(status().isOk());

        assertThat(FAKE.writeJournal()).extracting(FakeRouterOsServer.CapturedWrite::method)
                .containsExactly("PATCH", "PATCH", "DELETE");
        assertThat(json(FAKE.writeJournal().get(0).body()).path("parent").asText()).isEqualTo("none");
        assertThat(json(FAKE.writeJournal().get(1).body()).path("parent").asText()).isEqualTo("none");
        assertOnlyAllowlistedQueueWrites();
    }

    @Test
    void fastTrackBlocksBothBandwidthEndpointsWithoutAnyQueueWrite() throws Exception {
        FAKE.respondJson("/rest/ip/firewall/filter", "[{\".id\":\"*F1\",\"chain\":\"forward\",\"action\":\"fasttrack-connection\",\"disabled\":\"false\"}]");

        setPortSpeed(100_000_000, 20_000_000).andExpect(status().isConflict());
        setDeviceSpeed(MAC_A, 50_000_000, 15_000_000).andExpect(status().isConflict());

        assertThat(FAKE.writeJournal()).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions setPortSpeed(long download, long upload) throws Exception {
        return mockMvc.perform(put("/api/ports/ether2/speed").contentType(MediaType.APPLICATION_JSON)
                .content("{\"downloadBps\":" + download + ",\"uploadBps\":" + upload + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions setDeviceSpeed(String mac, long download, long upload) throws Exception {
        return mockMvc.perform(put("/api/devices/{mac}/speed", mac).contentType(MediaType.APPLICATION_JSON)
                .content("{\"downloadBps\":" + download + ",\"uploadBps\":" + upload + "}"));
    }

    private void setLeases(String ipA, String ipB) {
        FAKE.respondJson("/rest/ip/dhcp-server/lease", """
                [{".id":"*l1","mac-address":"AA:BB:CC:DD:EE:01","address":"%s","server":"dhcp-clientes","status":"bound","disabled":"false","dynamic":"false"},
                 {".id":"*l2","mac-address":"AA:BB:CC:DD:EE:02","address":"%s","server":"dhcp-clientes","status":"bound","disabled":"false","dynamic":"false"}]
                """.formatted(ipA, ipB));
    }

    private static String safeStandaloneChildren() {
        return """
                [{".id":"*Q1","name":"mtmgr-device-AABBCCDDEE01","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-01","target":"10.10.10.21/32","parent":"none","max-limit":"5M/40M","disabled":"false","dynamic":"false"},
                 {".id":"*Q2","name":"mtmgr-device-AABBCCDDEE02","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-02","target":"10.10.10.22/32","parent":"none","max-limit":"5M/40M","disabled":"false","dynamic":"false"}]
                """;
    }

    private static String parentWithChildren() {
        return """
                [{".id":"*P1","name":"mtmgr-port-ether2","comment":"MTMGR:PORT:ether2","target":"10.10.10.0/24","parent":"none","max-limit":"20M/100M","disabled":"false","dynamic":"false"},
                 {".id":"*Q1","name":"mtmgr-device-AABBCCDDEE01","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-01","target":"10.10.10.21/32","parent":"mtmgr-port-ether2","max-limit":"5M/40M","disabled":"false","dynamic":"false"},
                 {".id":"*Q2","name":"mtmgr-device-AABBCCDDEE02","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-02","target":"10.10.10.22/32","parent":"mtmgr-port-ether2","max-limit":"5M/40M","disabled":"false","dynamic":"false"}]
                """;
    }

    private static JsonNode json(String body) { try { return JSON.readTree(body); } catch (Exception e) { throw new AssertionError(e); } }
    private static void assertOnlyAllowlistedQueueWrites() {
        assertThat(FAKE.writeJournal()).allSatisfy(write -> {
            assertThat(write.method() + " " + write.path()).matches("PUT /rest/queue/simple|PATCH /rest/queue/simple/\\*[A-Za-z0-9]+|DELETE /rest/queue/simple/\\*[A-Za-z0-9]+");
            assertThat(write.principal()).isEqualTo("write-user");
        });
        assertThat(FAKE.requests()).noneMatch(request -> request.method().equals("POST") || request.path().contains("dhcp") && !request.method().equals("GET"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackRouterOsConfig {
        @Bean @Primary MikrotikGateway loopbackGateway(MikrotikProperties properties) {
            return new RouterOsRestGateway(properties,
                    RouterOsRestClient.forLoopbackHttpTest(properties, FAKE.baseUri(), RestClient.builder()), new RouterOsDhcpMapper());
        }
        @Bean @Primary BandwidthMutationGateway loopbackQueueWriter(MikrotikProperties properties) {
            return RouterOsQueueWriteClient.forLoopbackHttpTest(properties, FAKE.baseUri(), RestClient.builder());
        }
    }
}
