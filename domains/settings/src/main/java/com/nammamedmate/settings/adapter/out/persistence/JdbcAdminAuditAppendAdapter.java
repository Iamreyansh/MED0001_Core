package com.nammamedmate.settings.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.settings.application.port.out.AdminAuditAppendPort;
import com.nammamedmate.settings.domain.AuditRedaction;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAdminAuditAppendAdapter implements AdminAuditAppendPort {

  private static final Logger log = LoggerFactory.getLogger(JdbcAdminAuditAppendAdapter.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAdminAuditAppendAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void append(
      String entityType,
      UUID actorId,
      String actorRole,
      UUID entityId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    try {
      Map<String, Object> beforeRedacted = AuditRedaction.redactMap(before);
      Map<String, Object> afterRedacted = AuditRedaction.redactMap(after);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("before", beforeRedacted == null ? Map.of() : beforeRedacted);
      payload.put("after", afterRedacted == null ? Map.of() : afterRedacted);
      if (beforeRedacted != null) {
        Object from = beforeRedacted.get("role");
        if (from == null) {
          from = beforeRedacted.get("status");
        }
        if (from == null) {
          from = beforeRedacted.get("name");
        }
        if (from == null) {
          from = beforeRedacted.get("enabled");
        }
        if (from != null) {
          payload.put("from", String.valueOf(from));
        }
      }
      if (afterRedacted != null) {
        Object to = afterRedacted.get("role");
        if (to == null) {
          to = afterRedacted.get("status");
        }
        if (to == null) {
          to = afterRedacted.get("name");
        }
        if (to == null) {
          to = afterRedacted.get("enabled");
        }
        if (to != null) {
          payload.put("to", String.valueOf(to));
        }
      }
      String json = objectMapper.writeValueAsString(payload);
      String type =
          entityType == null || entityType.isBlank()
              ? "admin_staff"
              : entityType.trim().toLowerCase(Locale.ROOT);
      Instant now = Instant.now();
      String actorName = resolveActorName(actorId);
      jdbc.update(
          """
          INSERT INTO audit_log (
            id, entity_type, entity_id, action, actor_id, actor_role, payload, ip_address, created_at,
            actor_name, actor_type, resource_type, resource_id, before_state, after_state, metadata,
            user_agent, "timestamp"
          ) VALUES (
            ?, ?, ?, ?, ?, ?, ?::jsonb, CAST(? AS inet), ?,
            ?, 'ADMIN', ?, ?, ?::jsonb, ?::jsonb, NULL, NULL, ?
          )
          """,
          Ids.newId(),
          type,
          entityId,
          action,
          actorId,
          actorRole == null ? "unknown" : actorRole,
          json,
          "0.0.0.0",
          Timestamp.from(now),
          actorName,
          type,
          entityId,
          toJsonOrNull(beforeRedacted),
          toJsonOrNull(afterRedacted),
          Timestamp.from(now));
    } catch (RuntimeException | java.io.IOException ex) {
      log.warn(
          "Failed to append audit entityType={} action={}: {}", entityType, action, ex.toString());
    }
  }

  private String resolveActorName(UUID actorId) {
    if (actorId == null) {
      return "unknown";
    }
    try {
      String name =
          jdbc.query(
              "SELECT name FROM admin_staff WHERE id = ? LIMIT 1",
              rs -> rs.next() ? rs.getString(1) : null,
              actorId);
      return name == null || name.isBlank() ? "unknown" : name;
    } catch (RuntimeException ex) {
      return "unknown";
    }
  }

  private String toJsonOrNull(Map<String, Object> value) throws java.io.IOException {
    if (value == null) {
      return null;
    }
    return objectMapper.writeValueAsString(value);
  }
}
