package com.mikrotikmanager;

import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.MockMikrotikGateway;
import com.mikrotikmanager.persistence.ManagedDeviceRepository;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.service.DeviceService;
import com.mikrotikmanager.service.PortService;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "mikrotik.mock-mode=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MikrotikManagerApplicationIntegrationTest {
    private static final Path DATABASE_DIRECTORY = createTemporaryDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("mikrotik-manager-integration.db");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ManagedDeviceRepository managedDeviceRepository;

    @Autowired
    private ManagedPortRepository managedPortRepository;

    @Autowired
    private MikrotikGateway mikrotikGateway;

    @Autowired
    private PortService portService;

    @Autowired
    private DeviceService deviceService;

    @DynamicPropertySource
    static void useTemporarySqliteDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + toJdbcPath(DATABASE_FILE));
    }

    @Test
    void contextLoadsWithMockGatewayAndServices() {
        assertThat(applicationContext).isNotNull();
        assertThat(mikrotikGateway).isInstanceOf(MockMikrotikGateway.class);
        assertThat(portService.listPorts()).isNotEmpty();
        assertThat(deviceService.listDevices()).isNotEmpty();
    }

    @Test
    void flywayCreatesTablesAndRepositoriesPersistToTemporarySqlite() {
        assertThat(Files.exists(DATABASE_FILE)).isTrue();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name IN ('managed_port', 'managed_device', 'audit_log')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("PRAGMA table_info(managed_port)").stream()
                .map(column -> (String) column.get("name")))
                .contains("role");
        assertThat(managedPortRepository.findByInterfaceName("ether1"))
                .hasValueSatisfying(port -> assertThat(port.role().name()).isEqualTo("WAN"));
        assertThat(managedPortRepository.findByInterfaceName("ether2"))
                .hasValueSatisfying(port -> assertThat(port.role().name()).isEqualTo("CLIENT"));

        managedDeviceRepository.save("AA:BB:CC:DD:FE:01", "Dispositivo de integração", "SQLite temporário");

        assertThat(managedDeviceRepository.findByMacAddress("AA:BB:CC:DD:FE:01"))
                .hasValueSatisfying(device -> {
                    assertThat(device.friendlyName()).isEqualTo("Dispositivo de integração");
                    assertThat(device.notes()).isEqualTo("SQLite temporário");
                });
    }

    @AfterAll
    void removeTemporaryDatabase() throws IOException {
        dataSource.close();
        deleteRecursively(DATABASE_DIRECTORY);
    }

    private static Path createTemporaryDatabaseDirectory() {
        try {
            return Files.createTempDirectory("mikrotik-manager-integration-");
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível criar o diretório temporário do SQLite.", exception);
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
}
