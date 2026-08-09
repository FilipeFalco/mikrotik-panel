package com.mikrotikmanager.persistence;

import com.mikrotikmanager.domain.ManagedPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ManagedPortRepository {
    private final JdbcTemplate jdbcTemplate;

    public ManagedPortRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ManagedPort> findAll() {
        return jdbcTemplate.query("""
                SELECT id, interface_name, friendly_name, description, network, dhcp_server, enabled, created_at, updated_at
                FROM managed_port ORDER BY interface_name
                """, (rs, rowNum) -> map(rs));
    }

    public Optional<ManagedPort> findByInterfaceName(String interfaceName) {
        List<ManagedPort> ports = jdbcTemplate.query("""
                SELECT id, interface_name, friendly_name, description, network, dhcp_server, enabled, created_at, updated_at
                FROM managed_port WHERE interface_name = ?
                """, (rs, rowNum) -> map(rs), interfaceName);
        return ports.stream().findFirst();
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM managed_port", Long.class);
        return count == null ? 0 : count;
    }

    public ManagedPort save(ManagedPort port) {
        Instant now = Instant.now();
        Optional<ManagedPort> existing = findByInterfaceName(port.interfaceName());
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    UPDATE managed_port
                    SET friendly_name = ?, description = ?, network = ?, dhcp_server = ?, enabled = ?, updated_at = ?
                    WHERE interface_name = ?
                    """,
                    port.friendlyName(), nullable(port.description()), nullable(port.network()), nullable(port.dhcpServer()),
                    port.enabled() ? 1 : 0, now.toString(), port.interfaceName());
        } else {
            jdbcTemplate.update("""
                    INSERT INTO managed_port(interface_name, friendly_name, description, network, dhcp_server, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    port.interfaceName(), port.friendlyName(), nullable(port.description()), nullable(port.network()),
                    nullable(port.dhcpServer()), port.enabled() ? 1 : 0, now.toString(), now.toString());
        }
        return findByInterfaceName(port.interfaceName()).orElseThrow();
    }

    private ManagedPort map(ResultSet rs) throws SQLException {
        return new ManagedPort(
                rs.getLong("id"),
                rs.getString("interface_name"),
                rs.getString("friendly_name"),
                rs.getString("description"),
                rs.getString("network"),
                rs.getString("dhcp_server"),
                rs.getInt("enabled") == 1,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
