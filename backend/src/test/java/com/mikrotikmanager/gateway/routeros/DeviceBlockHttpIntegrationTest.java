package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikrotikmanager.MikrotikManagerApplication;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.gateway.DeviceBlockMutationGateway;
import com.mikrotikmanager.gateway.MikrotikGateway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full local HTTP path: controller -> executor -> real write transport -> stateful RouterOS fake. */
@SpringBootTest(classes = {MikrotikManagerApplication.class, DeviceBlockHttpIntegrationTest.LoopbackRouterOsConfig.class})
@AutoConfigureMockMvc
class DeviceBlockHttpIntegrationTest {
    private static final String MAC_A = "AA:BB:CC:DD:EE:01";
    private static final String MAC_B = "AA:BB:CC:DD:EE:02";
    private static final String COMMENT_A = "MTMGR:DEVICE:AA-BB-CC-DD-EE-01";
    private static final String COMMENT_B = "MTMGR:DEVICE:AA-BB-CC-DD-EE-02";
    private static final FakeRouterOsServer FAKE = FakeRouterOsServer.start();
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        configureReadFixtures();
        FAKE.enableStatefulFirewallFilters().requireCredentials("read-user", "read-password", "write-user", "write-password");
    }

    @Autowired private MockMvc mockMvc;

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
        registry.add("mikrotik.device-block-writes-enabled", () -> "true");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:/tmp/mikrotik-phase4-http-integration.db");
    }

    @BeforeEach
    void resetFake() {
        FAKE.seedFirewallFilters("""
                [{".id":"*fast","chain":"forward","action":"fasttrack-connection","disabled":"false","dynamic":"false"},
                 {".id":"*accept","chain":"forward","action":"accept","disabled":"false","dynamic":"false"}]
                """);
        FAKE.clearRequests();
    }

    @AfterAll
    static void closeFake() { FAKE.close(); }

    @Test
    void blockThenUnblockTraversesHttpControllerAndUsesSeparateCredentials() throws Exception {
        mockMvc.perform(post("/api/devices/{mac}/block", MAC_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.blockOwnership").value("MANAGED"));

        assertThat(FAKE.writeJournal()).singleElement().satisfies(write -> {
            assertThat(write.method()).isEqualTo("PUT");
            assertThat(write.path()).isEqualTo("/rest/ip/firewall/filter");
            assertThat(write.principal()).isEqualTo("write-user");
            JsonNode body = readJson(write.body());
            assertThat(body.path("chain").asText()).isEqualTo("forward");
            assertThat(body.path("action").asText()).isEqualTo("drop");
            assertThat(body.path("src-mac-address").asText()).isEqualTo(MAC_A);
            assertThat(body.path("comment").asText()).isEqualTo(COMMENT_A);
            assertThat(body.path("place-before").asText()).isEqualTo("*fast");
        });
        assertThat(FAKE.readJournal()).isNotEmpty().allSatisfy(read -> assertThat(read.principal()).isEqualTo("read-user"));

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].macAddress").value(MAC_A))
                .andExpect(jsonPath("$[0].blocked").value(true))
                .andExpect(jsonPath("$[0].blockOwnership").value("MANAGED"));

        mockMvc.perform(delete("/api/devices/{mac}/block", MAC_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false));
        assertThat(FAKE.writeJournal()).hasSize(2);
        assertThat(FAKE.writeJournal().get(1)).satisfies(write -> {
            assertThat(write.method()).isEqualTo("DELETE");
            assertThat(write.path()).matches("/rest/ip/firewall/filter/\\*FAKE\\d+");
            assertThat(write.principal()).isEqualTo("write-user");
        });
        assertAllowlistedWrites();
    }

    @Test
    void noOpDriftAndMultipleManagedPrefixHaveNoUnsafeWrite() throws Exception {
        FAKE.seedFirewallFilters("""
                [{".id":"*b","chain":"forward","action":"drop","src-mac-address":"AA:BB:CC:DD:EE:02","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-02","disabled":"false","dynamic":"false"},
                 {".id":"*a","chain":"forward","action":"drop","src-mac-address":"AA:BB:CC:DD:EE:01","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-01","disabled":"false","dynamic":"false"},
                 {".id":"*fast","chain":"forward","action":"fasttrack-connection","disabled":"false","dynamic":"false"}]
                """);
        FAKE.clearRequests();
        mockMvc.perform(post("/api/devices/{mac}/block", MAC_A)).andExpect(status().isOk());
        assertThat(FAKE.writeJournal()).isEmpty();

        FAKE.seedFirewallFilters("""
                [{".id":"*drift","chain":"forward","action":"drop","src-mac-address":"AA:BB:CC:DD:EE:01","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-01","protocol":"tcp","disabled":"false","dynamic":"false"},
                 {".id":"*fast","chain":"forward","action":"fasttrack-connection","disabled":"false","dynamic":"false"}]
                """);
        FAKE.clearRequests();
        mockMvc.perform(post("/api/devices/{mac}/block", MAC_A)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/devices/{mac}/block", MAC_A)).andExpect(status().isConflict());
        assertThat(FAKE.writeJournal()).isEmpty();
    }

    @Test
    void statefulFakeSmokeBlocksBothDevicesThenLeavesNoManagedRules() throws Exception {
        mockMvc.perform(post("/api/devices/{mac}/block", MAC_A)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/{mac}/block", MAC_B)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/{mac}/block", MAC_A)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/devices/{mac}/block", MAC_A)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/devices/{mac}/block", MAC_B)).andExpect(status().isOk());

        assertThat(FAKE.writeJournal()).hasSize(4);
        assertAllowlistedWrites();
        assertThat(FAKE.firewallFilterState()).doesNotContain("MTMGR:DEVICE:");
    }

    private static JsonNode readJson(String value) {
        try { return JSON.readTree(value); } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static void assertAllowlistedWrites() {
        assertThat(FAKE.writeJournal()).allSatisfy(write -> assertThat(write.method() + " " + write.path())
                .matches("PUT /rest/ip/firewall/filter|DELETE /rest/ip/firewall/filter/\\*[A-Za-z0-9]+"));
        assertThat(FAKE.requests()).noneMatch(request -> request.method().equals("POST") || request.method().equals("PATCH"));
    }

    private static void configureReadFixtures() {
        FAKE.respondJson("/rest/interface", "[{\".id\":\"*2\",\"name\":\"ether2\",\"type\":\"ether\",\"running\":\"true\",\"disabled\":\"false\"}]");
        FAKE.respondJson("/rest/ip/dhcp-server", "[{\".id\":\"*d\",\"name\":\"dhcp-clientes\",\"interface\":\"ether2\",\"disabled\":\"false\"}]");
        FAKE.respondJson("/rest/ip/dhcp-server/lease", """
                [{".id":"*l1","mac-address":"AA:BB:CC:DD:EE:01","address":"10.10.10.21","server":"dhcp-clientes","status":"bound","disabled":"false","dynamic":"false"},
                 {".id":"*l2","mac-address":"AA:BB:CC:DD:EE:02","address":"10.10.10.22","server":"dhcp-clientes","status":"bound","disabled":"false","dynamic":"false"}]
                """);
        FAKE.respondJson("/rest/queue/simple", "[]");
        FAKE.respondJson("/rest/ip/firewall/address-list", "[]");
        FAKE.respondJson("/rest/system/resource", "[{\".id\":\"*0\",\"version\":\"7.21\"}]");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackRouterOsConfig {
        @Bean @Primary
        MikrotikGateway loopbackGateway(MikrotikProperties properties) {
            return new RouterOsRestGateway(properties,
                    RouterOsRestClient.forLoopbackHttpTest(properties, FAKE.baseUri(), RestClient.builder()),
                    new RouterOsDhcpMapper());
        }

        @Bean @Primary
        DeviceBlockMutationGateway loopbackWriter(MikrotikProperties properties) {
            return RouterOsWriteClient.forLoopbackHttpTest(properties, FAKE.baseUri(), RestClient.builder());
        }
    }
}
