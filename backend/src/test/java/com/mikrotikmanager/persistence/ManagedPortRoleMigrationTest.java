package com.mikrotikmanager.persistence;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedPortRoleMigrationTest {
    @Test
    void upgradesAV1SqliteDatabaseWithASafeClientRoleForExistingManagedPorts() throws IOException {
        Path database = Files.createTempFile("mikrotik-managed-port-v1-", ".db");
        try {
            DriverManagerDataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("1"))
                    .load()
                    .migrate();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.update("""
                    INSERT INTO managed_port(interface_name, friendly_name, description, network, dhcp_server, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "uplink-legado", "Uplink existente", null, null, null, 1,
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load();
            flyway.migrate();

            assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("2"));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT role FROM managed_port WHERE interface_name = 'uplink-legado'", String.class))
                    .isEqualTo("CLIENT");
            assertThat(jdbcTemplate.queryForList("PRAGMA table_info(managed_port)").stream()
                    .map(column -> (String) column.get("name")))
                    .contains("role");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void persistsRolesAndMovesTheSingleLocalWanWithoutUsingInterfaceNamesAsRules() throws IOException {
        Path database = Files.createTempFile("mikrotik-managed-port-role-", ".db");
        try {
            DriverManagerDataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            ManagedPortRepository repository = new ManagedPortRepository(new JdbcTemplate(dataSource));

            repository.save(port("ether5", ManagedPortRole.WAN));
            repository.save(port("uplink-fibra", ManagedPortRole.WAN));
            repository.save(port("cliente-pon", ManagedPortRole.CLIENT));

            List<ManagedPort> ports = repository.findAll();
            assertThat(ports).filteredOn(port -> port.role() == ManagedPortRole.WAN)
                    .extracting(ManagedPort::interfaceName)
                    .containsExactly("uplink-fibra");
            assertThat(repository.findByInterfaceName("ether5")).hasValueSatisfying(port ->
                    assertThat(port.role()).isEqualTo(ManagedPortRole.CLIENT));
            assertThat(repository.findByInterfaceName("cliente-pon")).hasValueSatisfying(port ->
                    assertThat(port.role()).isEqualTo(ManagedPortRole.CLIENT));
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private DriverManagerDataSource dataSource(Path database) {
        return new DriverManagerDataSource("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
    }

    private ManagedPort port(String interfaceName, ManagedPortRole role) {
        Instant now = Instant.now();
        return new ManagedPort(0, interfaceName, interfaceName, null, null, null, role, true, now, now);
    }
}
