package com.mikrotikmanager.persistence;

import com.mikrotikmanager.domain.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class AuditLogRepository {
    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String action, String targetType, String targetIdentifier, String previousValue,
                       String newValue, boolean success, String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO audit_log(created_at, action, target_type, target_identifier, previous_value, new_value, success, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Instant.now().toString(), action, targetType, targetIdentifier, previousValue, newValue,
                success ? 1 : 0, errorMessage);
    }

    public List<AuditLog> findRecent(int limit) {
        return jdbcTemplate.query("""
                SELECT id, created_at, action, target_type, target_identifier, previous_value, new_value, success, error_message
                FROM audit_log ORDER BY created_at DESC LIMIT ?
                """, (rs, rowNum) -> map(rs), limit);
    }

    private AuditLog map(ResultSet rs) throws SQLException {
        return new AuditLog(
                rs.getLong("id"), Instant.parse(rs.getString("created_at")), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_identifier"), rs.getString("previous_value"),
                rs.getString("new_value"), rs.getInt("success") == 1, rs.getString("error_message")
        );
    }
}
