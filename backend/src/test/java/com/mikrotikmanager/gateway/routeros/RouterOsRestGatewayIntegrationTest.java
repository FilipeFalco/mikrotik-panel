package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.GatewayDiagnostics;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.GatewayErrorType;
import com.mikrotikmanager.gateway.MikrotikGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterOsRestGatewayIntegrationTest {
    private static final String SYSTEM_RESOURCE = "/rest/system/resource";
    private static final String INTERFACES = "/rest/interface";
    private static final String DHCP_SERVERS = "/rest/ip/dhcp-server";
    private static final String DHCP_LEASES = "/rest/ip/dhcp-server/lease";
    private static final String SIMPLE_QUEUES = "/rest/queue/simple";
    private static final String FIREWALL_FILTERS = "/rest/ip/firewall/filter";
    private static final String FIREWALL_ADDRESS_LISTS = "/rest/ip/firewall/address-list";

    @Test
    void mapsRealisticReadOnlyRouterOsFixturesAndUsesOnlyGetRequests() {
        try (FakeRouterOsServer fake = FakeRouterOsServer.start()) {
            stubHealthyRouter(fake);
            RouterOsRestGateway gateway = gateway(fake, false);

            GatewayConnectionStatus status = gateway.connectionStatus();
            List<RouterInterface> interfaces = gateway.listInterfaces();
            List<RouterDevice> devices = gateway.listDevices();
            Map<String, SpeedLimit> portSpeeds = gateway.listPortSpeeds();

            // Firewall filters are deliberately on-demand, not part of regular polling reads.
            assertThat(fake.requestCount(FIREWALL_FILTERS)).isZero();
            GatewayDiagnostics diagnostics = gateway.diagnostics();

            assertThat(status.connected()).isTrue();
            assertThat(status.routerOsVersion()).isEqualTo("7.18.2 (stable)");
            assertThat(status.latencyMillis()).isGreaterThanOrEqualTo(0L);

            assertThat(interfaces).extracting(RouterInterface::name).containsExactly("ether1", "ether2", "ether3");
            assertThat(interfaces).extracting(RouterInterface::running).containsExactly(true, true, false);

            assertThat(devices).extracting(RouterDevice::hostname).containsExactly("Galaxy S25", "Notebook", null);
            assertThat(devices).filteredOn(device -> "Galaxy S25".equals(device.hostname())).singleElement()
                    .satisfies(device -> {
                        assertThat(device.interfaceName()).isEqualTo("ether2");
                        assertThat(device.macAddress()).isEqualTo("AA:BB:CC:DD:EE:01");
                        assertThat(device.status()).isEqualTo(DeviceStatus.ONLINE);
                    });
            assertThat(devices).filteredOn(RouterDevice::blocked).singleElement()
                    .extracting(RouterDevice::status).isEqualTo(DeviceStatus.BLOCKED);

            // RouterOS max-limit is upload/download; application SpeedLimit is download/upload.
            assertThat(portSpeeds).containsExactly(Map.entry("ether2", new SpeedLimit(20_000_000L, 5_000_000L)));
            assertThat(diagnostics.fastTrackDetected()).isTrue();
            assertThat(diagnostics.checks()).filteredOn(check -> "FastTrack".equals(check.name())).singleElement()
                    .satisfies(check -> assertThat(check.available()).isTrue());
            assertThat(fake.requestCount(FIREWALL_FILTERS)).isEqualTo(1);

            assertThat(fake.requests()).isNotEmpty().allSatisfy(request ->
                    assertThat(request.method()).isEqualTo("GET"));
            assertThat(fake.requests()).extracting(FakeRouterOsServer.CapturedRequest::path)
                    .contains(SYSTEM_RESOURCE, INTERFACES, DHCP_SERVERS, DHCP_LEASES, SIMPLE_QUEUES, FIREWALL_FILTERS);
        }
    }

    @Test
    void listsAnyNumberOfDevicesWithExactlyOneDhcpServerAndOneLeaseRequest() {
        try (FakeRouterOsServer fake = FakeRouterOsServer.start()) {
            stubHealthyRouter(fake);
            RouterOsRestGateway gateway = gateway(fake, false);

            fake.clearRequests();
            assertThat(gateway.listDevices()).hasSize(3);

            assertThat(fake.requests()).hasSize(3);
            assertThat(fake.requestCount(DHCP_SERVERS)).isEqualTo(1);
            assertThat(fake.requestCount(DHCP_LEASES)).isEqualTo(1);
            assertThat(fake.requestCount(SIMPLE_QUEUES)).isEqualTo(1);
            assertThat(fake.requests()).allSatisfy(request -> assertThat(request.method()).isEqualTo("GET"));
        }
    }

    @Test
    void capturesOneBatchedSnapshotWithConstantRouterRequestCountForOneHundredLeases() {
        try (FakeRouterOsServer fake = FakeRouterOsServer.start()) {
            stubHealthyRouter(fake);
            RouterOsRestGateway gateway = gateway(fake, false);

            fake.respondJson(DHCP_LEASES, largeLeaseFixture(1));
            var oneLeaseSnapshot = gateway.captureSnapshot();
            int oneLeaseRequestCount = fake.requests().size();
            fake.clearRequests();

            fake.respondJson(DHCP_LEASES, largeLeaseFixture(100));
            var oneHundredLeaseSnapshot = gateway.captureSnapshot();

            assertThat(oneLeaseSnapshot.dhcpLeases()).hasSize(1);
            assertThat(oneHundredLeaseSnapshot.dhcpLeases()).hasSize(100);
            assertThat(oneHundredLeaseSnapshot.devices()).hasSize(100);
            assertThat(fake.requests()).hasSize(oneLeaseRequestCount);
            assertThat(fake.requests()).hasSize(6);
            assertThat(fake.requestCount(INTERFACES)).isEqualTo(1);
            assertThat(fake.requestCount(DHCP_SERVERS)).isEqualTo(1);
            assertThat(fake.requestCount(DHCP_LEASES)).isEqualTo(1);
            assertThat(fake.requestCount(SIMPLE_QUEUES)).isEqualTo(1);
            assertThat(fake.requestCount(FIREWALL_FILTERS)).isEqualTo(1);
            assertThat(fake.requestCount(FIREWALL_ADDRESS_LISTS)).isEqualTo(1);
            assertThat(fake.requests()).allSatisfy(request -> assertThat(request.method()).isEqualTo("GET"));
        }
    }

    @Test
    void treatsACompletedEmptyQueueCollectionAsAValidResult() {
        try (FakeRouterOsServer fake = FakeRouterOsServer.start()) {
            fake.respondJson(SIMPLE_QUEUES, "[]");
            RouterOsRestGateway gateway = gateway(fake, false);

            assertThat(gateway.listPortSpeeds()).isEmpty();
            assertThat(fake.requestCount(SIMPLE_QUEUES)).isEqualTo(1);
            assertThat(fake.requests()).allSatisfy(request -> assertThat(request.method()).isEqualTo("GET"));
        }
    }

    @Test
    void mapsAuthenticationFailureToSanitizedTypedGatewayFailure() {
        String password = "fake-routeros-password-must-not-leak";
        try (FakeRouterOsServer fake = FakeRouterOsServer.start()) {
            fake.respond(SYSTEM_RESOURCE, 401, "{\"message\":\"denied " + password + "\"}");
            fake.respond(INTERFACES, 401, "{\"message\":\"denied " + password + "\"}");
            RouterOsRestGateway gateway = gateway(fake, false, password);

            assertThatThrownBy(gateway::listInterfaces)
                    .isInstanceOfSatisfying(MikrotikGatewayException.class, exception -> {
                        assertThat(exception.errorType()).isEqualTo(GatewayErrorType.AUTHENTICATION_FAILED);
                        assertThat(exception.getMessage()).doesNotContain(password);
                    });

            GatewayConnectionStatus status = gateway.connectionStatus();
            assertThat(status.connected()).isFalse();
            assertThat(status.message()).doesNotContain(password);
        }
    }

    @Test
    void mapsMalformedJsonToControlledGatewayFailureWithoutLeakingResponseContent() {
        String sensitiveBodyFragment = "router-response-must-not-leak";
        try (FakeRouterOsServer fake = FakeRouterOsServer.start()) {
            fake.respondJson(INTERFACES, "{ invalid " + sensitiveBodyFragment);
            RouterOsRestGateway gateway = gateway(fake, false);

            assertThatThrownBy(gateway::listInterfaces)
                    .isInstanceOfSatisfying(MikrotikGatewayException.class, exception -> {
                        assertThat(exception.errorType()).isEqualTo(GatewayErrorType.BAD_RESPONSE);
                        assertThat(exception.getMessage()).doesNotContain(sensitiveBodyFragment);
                    });
        }
    }

    @Test
    void returnsDisconnectedStatusWhenLoopbackRouterIsUnavailable() {
        FakeRouterOsServer fake = FakeRouterOsServer.start();
        URI baseUri = fake.baseUri();
        MikrotikProperties properties = properties(baseUri, false, "unavailable-test-password");
        RouterOsRestGateway gateway = new RouterOsRestGateway(
                properties,
                RouterOsRestClient.forLoopbackHttpTest(properties, baseUri, RestClient.builder()),
                new RouterOsDhcpMapper()
        );
        fake.close();

        GatewayConnectionStatus status = gateway.connectionStatus();

        assertThat(status.connected()).isFalse();
        assertThat(status.message()).doesNotContain("unavailable-test-password");
    }

    @Test
    void directMutationMethodsStayUnimplementedEvenIfWritesAreConfiguredAndSendNoHttpRequests() {
        try (FakeRouterOsServer fake = FakeRouterOsServer.start()) {
            RouterOsRestGateway gateway = gateway(fake, true);

            assertWriteNotImplemented(() -> gateway.setPortSpeed("ether2", new SpeedLimit(20_000_000L, 5_000_000L)));
            assertWriteNotImplemented(() -> gateway.setDeviceSpeed("AA:BB:CC:DD:EE:01", new SpeedLimit(20_000_000L, 5_000_000L)));
            assertWriteNotImplemented(() -> gateway.blockDevice("AA:BB:CC:DD:EE:01"));
            assertWriteNotImplemented(() -> gateway.unblockDevice("AA:BB:CC:DD:EE:01"));

            assertThat(fake.requests()).isEmpty();
        }
    }

    private static void assertWriteNotImplemented(ThrowingRunnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(MikrotikGatewayException.class, exception ->
                        assertThat(exception.errorType()).isEqualTo(GatewayErrorType.WRITE_NOT_IMPLEMENTED));
    }

    private static RouterOsRestGateway gateway(FakeRouterOsServer fake, boolean writeEnabled) {
        return gateway(fake, writeEnabled, "test-password");
    }

    private static RouterOsRestGateway gateway(FakeRouterOsServer fake, boolean writeEnabled, String password) {
        MikrotikProperties properties = properties(fake.baseUri(), writeEnabled, password);
        RouterOsRestClient client = RouterOsRestClient.forLoopbackHttpTest(
                properties, fake.baseUri(), RestClient.builder());
        return new RouterOsRestGateway(properties, client, new RouterOsDhcpMapper());
    }

    private static MikrotikProperties properties(URI baseUri, boolean writeEnabled, String password) {
        return new MikrotikProperties(
                "127.0.0.1",
                baseUri.getPort(),
                "mtmgr-test",
                password,
                true,
                false,
                writeEnabled,
                500,
                1_000
        );
    }

    private static void stubHealthyRouter(FakeRouterOsServer fake) {
        fake.respondJson(SYSTEM_RESOURCE, """
                [{
                  ".id": "*0",
                  "version": "7.18.2 (stable)",
                  "uptime": "2d3h4m",
                  "cpu-load": "17",
                  "architecture-name": "arm",
                  "board-name": "hEX S"
                }]
                """);
        fake.respondJson(INTERFACES, """
                [
                  {".id":"*1","name":"ether1","type":"ether","running":"true","disabled":"false"},
                  {".id":"*2","name":"ether2","type":"ether","running":"true","disabled":"false"},
                  {".id":"*3","name":"ether3","type":"ether","running":"false","disabled":"false"}
                ]
                """);
        fake.respondJson(DHCP_SERVERS, """
                [
                  {".id":"*11","name":"dhcp-cliente1","interface":"ether2","disabled":"false"},
                  {".id":"*12","name":"dhcp-cliente2","interface":"ether3","disabled":"false"}
                ]
                """);
        fake.respondJson(DHCP_LEASES, """
                [
                  {
                    ".id":"*A1","address":"10.10.10.21","mac-address":"aa:bb:cc:dd:ee:01",
                    "host-name":"Galaxy S25","server":"dhcp-cliente1","status":"bound",
                    "block-access":"false","comment":"Celular principal","last-seen":"2m","rate-limit":"512k/1M",
                    "dynamic":"true","disabled":"false"
                  },
                  {
                    ".id":"*A2","address":"10.10.10.22","mac-address":"AA:BB:CC:DD:EE:02",
                    "host-name":"Notebook","server":"dhcp-cliente1","status":"waiting",
                    "block-access":"false","last-seen":"never","dynamic":"true","disabled":"false"
                  },
                  {
                    ".id":"*A3","address":"10.10.20.23","mac-address":"AA:BB:CC:DD:EE:03",
                    "server":"dhcp-cliente2","status":"bound","block-access":"true",
                    "comment":"Bloqueado","dynamic":"true","disabled":"false"
                  },
                  {
                    ".id":"*A4","address":"10.10.99.5","mac-address":"AA:BB:CC:DD:EE:04",
                    "host-name":"Lease sem servidor","server":"dhcp-inexistente","status":"bound",
                    "block-access":"false","dynamic":"true","disabled":"false"
                  }
                ]
                """);
        fake.respondJson(SIMPLE_QUEUES, """
                [
                  {
                    ".id":"*20","name":"mtmgr-port-ether2","comment":"MTMGR:PORT:ether2",
                    "max-limit":"5M/20M","disabled":"false","dynamic":"false","target":"10.10.10.0/24"
                  },
                  {
                    ".id":"*21","name":"manual-queue","comment":"Minha queue manual",
                    "max-limit":"1M/50M","disabled":"false","dynamic":"false","target":"10.10.10.0/24"
                  }
                ]
                """);
        fake.respondJson(FIREWALL_FILTERS, """
                [
                  {
                    ".id":"*30","action":"fasttrack-connection","disabled":"false","dynamic":"false",
                    "chain":"forward","comment":"defconf: fasttrack"
                  },
                  {
                    ".id":"*31","action":"fasttrack-connection","disabled":"true","dynamic":"false",
                    "chain":"forward","comment":"disabled fasttrack"
                  }
                ]
                """);
        fake.respondJson(FIREWALL_ADDRESS_LISTS, "[]");
    }

    private static String largeLeaseFixture(int leaseCount) {
        return IntStream.range(0, leaseCount)
                .mapToObj(index -> """
                        {".id":"*%s","address":"10.10.10.%s","mac-address":"AA:BB:CC:DD:EE:%02X",
                         "host-name":"Device %s","server":"dhcp-cliente1","status":"bound",
                         "block-access":"false","dynamic":"true","disabled":"false"}
                        """.formatted(index, (index % 250) + 1, index, index))
                .collect(Collectors.joining(",", "[", "]"));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
