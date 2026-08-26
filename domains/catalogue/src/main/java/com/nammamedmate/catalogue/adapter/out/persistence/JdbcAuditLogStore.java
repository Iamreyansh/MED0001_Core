package com.nammamedmate.catalogue.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("catalogueJdbcAuditLogStore")
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
    String beforeJson;
    String afterJson;
    try {
      Map<String, Object> payload = record.payload();
      payloadJson = objectMapper.writeValueAsString(payload);
      if (payload.containsKey("before") || payload.containsKey("after")) {
        Object before = payload.get("before");
        Object after = payload.get("after");
        beforeJson = before == null ? null : objectMapper.writeValueAsString(before);
        afterJson = after == null ? null : objectMapper.writeValueAsString(after);
      } else {
        beforeJson = null;
        afterJson = payload.isEmpty() ? null : payloadJson;
      }
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
    String entityType = record.entityType();
    String resourceType =
        entityType == null ? "unknown" : entityType.trim().toLowerCase(Locale.ROOT);
    jdbc.update(
        """
        INSERT INTO audit_log (
          id, entity_type, entity_id, action, actor_id, actor_role, payload, ip_address, created_at,
          actor_name, actor_type, resource_type, resource_id, before_state, after_state, metadata,
          user_agent, "timestamp"
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?::jsonb, CAST(? AS inet), ?,
          ?, ?, ?, ?, ?::jsonb, ?::jsonb, NULL, NULL, ?
        )
        """,
        record.id(),
        entityType,
        record.entityId(),
        record.action(),
        record.actorId(),
        record.actorRole(),
        payloadJson,
        blankToDefaultIp(record.ipAddress()),
        Timestamp.from(record.createdAt()),
        resolveActorName(record.actorId(), record.actorRole()),
        actorTypeForRole(record.actorRole()),
        resourceType,
        record.entityId(),
        beforeJson,
        afterJson,
        Timestamp.from(record.createdAt()));
  }

  private static String blankToDefaultIp(String value) {
    if (value == null || value.isBlank()) {
      return "0.0.0.0";
    }
    return value.trim();
  }

  private String resolveActorName(java.util.UUID actorId, String actorRole) {
    if (actorId == null) {
      return "unknown";
    }
    var staff =
        jdbc.query(
            """
            SELECT COALESCE(NULLIF(TRIM(name), ''), email) AS label
            FROM pharmacy_staff
            WHERE id = ? AND deleted_at IS NULL
            LIMIT 1
            """,
            (rs, i) -> rs.getString("label"),
            actorId);
    if (staff != null
        && !staff.isEmpty()
        && staff.getFirst() != null
        && !staff.getFirst().isBlank()) {
      return staff.getFirst();
    }
    if (actorRole != null && actorRole.startsWith("ADMIN")) {
      var admin =
          jdbc.query(
              """
              SELECT COALESCE(NULLIF(TRIM(name), ''), email) AS label
              FROM admin_staff
              WHERE id = ? AND deleted_at IS NULL
              LIMIT 1
              """,
              (rs, i) -> rs.getString("label"),
              actorId);
      if (admin != null
          && !admin.isEmpty()
          && admin.getFirst() != null
          && !admin.getFirst().isBlank()) {
        return admin.getFirst();
      }
    }
    return "unknown";
  }

  private static String actorTypeForRole(String actorRole) {
    if (actorRole != null && actorRole.toUpperCase(Locale.ROOT).startsWith("ADMIN")) {
      return "ADMIN";
    }
    return "ADMIN";
  }
}
