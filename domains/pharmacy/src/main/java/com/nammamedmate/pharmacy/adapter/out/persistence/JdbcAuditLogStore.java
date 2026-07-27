package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAuditLogStore implements AuditLogStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAuditLogStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void append(AuditLogRecord record) {
    String payloadJson;
    try {
      payloadJson = objectMapper.writeValueAsString(record.payload());
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
    jdbc.update(
        """
        INSERT INTO audit_log (
          id, entity_type, entity_id, action, actor_id, actor_role, payload, ip_address, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, CAST(? AS inet), ?)
        """,
        record.id(),
        record.entityType(),
        record.entityId(),
        record.action(),
        record.actorId(),
        record.actorRole(),
        payloadJson,
        blankToNull(record.ipAddress()),
        Timestamp.from(record.createdAt()));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
