package com.mikrotikmanager.persistence;

import com.mikrotikmanager.domain.DeviceMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ManagedDeviceRepository {
    private final JdbcTemplate jdbcTemplate;

    public ManagedDeviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DeviceMetadata> findByMacAddress(String macAddress) {
        List<DeviceMetadata> result = jdbcTemplate.query("""
                SELECT id, mac_address, friendly_name, notes, created_at, updated_at
                FROM managed_device WHERE mac_address = ?
                """, (rs, rowNum) -> map(rs), macAddress);
        return result.stream().findFirst();
    }

    public DeviceMetadata save(String macAddress, String friendlyName, String notes) {
        Instant now = Instant.now();
        Optional<DeviceMetadata> existing = findByMacAddress(macAddress);
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    UPDATE managed_device SET friendly_name = ?, notes = ?, updated_at = ? WHERE mac_address = ?
                    """, nullable(friendlyName), nullable(notes), now.toString(), macAddress);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO managed_device(mac_address, friendly_name, notes, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, macAddress, nullable(friendlyName), nullable(notes), now.toString(), now.toString());
        }
        return findByMacAddress(macAddress).orElseThrow();
    }

    private DeviceMetadata map(ResultSet rs) throws SQLException {
        return new DeviceMetadata(
                rs.getLong("id"), rs.getString("mac_address"), rs.getString("friendly_name"), rs.getString("notes"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at"))
        );
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
