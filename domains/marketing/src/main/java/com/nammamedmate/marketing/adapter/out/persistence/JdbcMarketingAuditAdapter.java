package com.nammamedmate.marketing.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.MarketingAuditPort;
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
public class JdbcMarketingAuditAdapter implements MarketingAuditPort {

  private static final Logger log = LoggerFactory.getLogger(JdbcMarketingAuditAdapter.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcMarketingAuditAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
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
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("before", before == null ? Map.of() : before);
      payload.put("after", after == null ? Map.of() : after);
      String json = objectMapper.writeValueAsString(payload);
      Instant now = Instant.now();
      String type =
          entityType == null || entityType.isBlank()
              ? "banner"
              : entityType.trim().toLowerCase(Locale.ROOT);
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
          "admin",
          type,
          entityId,
          before == null ? null : objectMapper.writeValueAsString(before),
          after == null ? null : objectMapper.writeValueAsString(after),
          Timestamp.from(now));
    } catch (RuntimeException | java.io.IOException ex) {
      log.warn("Failed to append marketing audit action={}: {}", action, ex.toString());
    }
  }
}
