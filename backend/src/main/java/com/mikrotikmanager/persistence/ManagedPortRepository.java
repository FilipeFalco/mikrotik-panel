package com.mikrotikmanager.persistence;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
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
                SELECT id, interface_name, friendly_name, description, network, dhcp_server, role, enabled, created_at, updated_at
                FROM managed_port ORDER BY interface_name
                """, (rs, rowNum) -> map(rs));
    }

    public Optional<ManagedPort> findByInterfaceName(String interfaceName) {
        List<ManagedPort> ports = jdbcTemplate.query("""
                SELECT id, interface_name, friendly_name, description, network, dhcp_server, role, enabled, created_at, updated_at
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
        if (port.role() == ManagedPortRole.WAN) {
            makeOtherWanPortsClients(port.interfaceName(), now);
        }
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    UPDATE managed_port
                    SET friendly_name = ?, description = ?, network = ?, dhcp_server = ?, role = ?, enabled = ?, updated_at = ?
                    WHERE interface_name = ?
                    """,
                    port.friendlyName(), nullable(port.description()), nullable(port.network()), nullable(port.dhcpServer()),
                    role(port.role()), port.enabled() ? 1 : 0, now.toString(), port.interfaceName());
        } else {
            jdbcTemplate.update("""
                    INSERT INTO managed_port(interface_name, friendly_name, description, network, dhcp_server, role, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    port.interfaceName(), port.friendlyName(), nullable(port.description()), nullable(port.network()),
                    nullable(port.dhcpServer()), role(port.role()), port.enabled() ? 1 : 0, now.toString(), now.toString());
        }
        return findByInterfaceName(port.interfaceName()).orElseThrow();
    }

    /**
     * Keeps the local presentation model deterministic for the MVP: one
     * configured WAN at a time. This is an SQLite-only metadata update.
     */
    private void makeOtherWanPortsClients(String interfaceName, Instant now) {
        jdbcTemplate.update("""
                UPDATE managed_port
                SET role = ?, updated_at = ?
                WHERE role = ? AND interface_name <> ?
                """, ManagedPortRole.CLIENT.name(), now.toString(), ManagedPortRole.WAN.name(), interfaceName);
    }

    private ManagedPort map(ResultSet rs) throws SQLException {
        return new ManagedPort(
                rs.getLong("id"),
                rs.getString("interface_name"),
                rs.getString("friendly_name"),
                rs.getString("description"),
                rs.getString("network"),
                rs.getString("dhcp_server"),
                role(rs.getString("role")),
                rs.getInt("enabled") == 1,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String role(ManagedPortRole role) {
        return role == null ? null : role.name();
    }

    private ManagedPortRole role(String value) {
        return value == null ? null : ManagedPortRole.valueOf(value);
    }
}
