package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.TriggerEventQueryPort;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.kernel.id.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTriggerEventStore implements TriggerEventStorePort, TriggerEventQueryPort {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcTriggerEventStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public UUID insert(
      String triggerId,
      String entityType,
      UUID entityId,
      Map<String, Object> payload,
      Instant firedAt) {
    UUID id = Ids.newId();
    String json;
    try {
      json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (Exception ex) {
      json = "{}";
    }
    jdbc.update(
        """
        INSERT INTO trigger_events (
          id, trigger_id, entity_type, entity_id, payload, fired_at,
          rules_evaluated, rules_fired
        ) VALUES (?, ?, ?, ?, ?::jsonb, ?, 0, 0)
        """,
        id,
        triggerId,
        entityType,
        entityId,
        json,
        Timestamp.from(firedAt));
    return id;
  }

  @Override
  public void markProcessed(
      UUID eventId, Instant processedAt, int rulesEvaluated, int rulesFired, String outcome) {
    jdbc.update(
        """
        UPDATE trigger_events
        SET processed_at = ?, rules_evaluated = ?, rules_fired = ?, outcome = ?
        WHERE id = ?
        """,
        Timestamp.from(processedAt),
        rulesEvaluated,
        rulesFired,
        outcome,
        eventId);
  }

  @Override
  public List<TriggerEventRow> listRecentByTrigger(String triggerId, int limit) {
    return jdbc.query(
        """
        SELECT id, trigger_id, entity_type, entity_id, payload::text AS payload, fired_at
        FROM trigger_events
        WHERE trigger_id = ?
        ORDER BY fired_at DESC
        LIMIT ?
        """,
        (rs, i) ->
            new TriggerEventRow(
                (UUID) rs.getObject("id"),
                rs.getString("trigger_id"),
                rs.getString("entity_type"),
                (UUID) rs.getObject("entity_id"),
                readMap(rs.getString("payload")),
                rs.getTimestamp("fired_at").toInstant()),
        triggerId,
        limit);
  }

  @Override
  public Optional<TriggerEventRow> findLatestByEntity(String entityType, UUID entityId) {
    List<TriggerEventRow> rows =
        jdbc.query(
            """
            SELECT id, trigger_id, entity_type, entity_id, payload::text AS payload, fired_at
            FROM trigger_events
            WHERE entity_type = ? AND entity_id = ?
            ORDER BY fired_at DESC
            LIMIT 1
            """,
            (rs, i) ->
                new TriggerEventRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("trigger_id"),
                    rs.getString("entity_type"),
                    (UUID) rs.getObject("entity_id"),
                    readMap(rs.getString("payload")),
                    rs.getTimestamp("fired_at").toInstant()),
            entityType,
            entityId);
    return rows.stream().findFirst();
  }

  private Map<String, Object> readMap(String json) {
    if (json == null) {
      return Map.of();
    }
    if (json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (Exception ex) {
      return Map.of();
    }
  }
}
